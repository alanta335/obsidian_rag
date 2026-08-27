package com.rag.obsidian_rag.rag;

import com.rag.obsidian_rag.api.dto.ChatResponse;
import com.rag.obsidian_rag.api.dto.CitationDto;
import com.rag.obsidian_rag.api.dto.SearchResponse;
import com.rag.obsidian_rag.api.dto.SourceItem;
import com.rag.obsidian_rag.config.VaultProperties;
import com.rag.obsidian_rag.graph.LinkGraphService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates retrieval, grounded generation, citation validation and the
 * optional faithfulness judge. Retrieval is performed here (not inside a
 * RetrievalAugmentor) so that exactly the fragments sent to the model are
 * known and auditable.
 */
@Slf4j
@Service
public class RagService {

    private static final int SNIPPET_CHARS = 200;

    private final Assistant assistant;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel queryEmbeddingModel;
    private final LinkGraphService graph;
    private final FaithfulnessService faithfulness;
    private final VaultProperties props;

    /**
     * Creates the orchestration service.
     *
     * @param assistant           structured-output chat pipeline
     * @param store               pgvector embedding store holding all indexed fragments
     * @param queryEmbeddingModel query-mode embedding model; must NOT be swapped for
     *                            the passage model (asymmetric NVIDIA embeddings)
     * @param graph               link graph used for linked-context expansion
     * @param faithfulness        citation validation and entailment judge
     * @param props               vault/retrieval/faithfulness configuration
     */
    public RagService(Assistant assistant,
                      EmbeddingStore<TextSegment> store,
                      @Qualifier("queryEmbeddingModel") EmbeddingModel queryEmbeddingModel,
                      LinkGraphService graph,
                      FaithfulnessService faithfulness,
                      VaultProperties props) {
        this.assistant = assistant;
        this.store = store;
        this.queryEmbeddingModel = queryEmbeddingModel;
        this.graph = graph;
        this.faithfulness = faithfulness;
        this.props = props;
    }

    /**
     * Answers a question over the vault with grounded generation.
     *
     * <p>Pipeline: embed the question (query model) → vector search → refusal when
     * fewer than {@code minResults} fragments pass {@code minScore} (the LLM is never
     * called in that case) → optional linked-context expansion → structured
     * {@link RagAnswer} → citation validation → optional faithfulness judging with at
     * most one regeneration attempt. A regeneration replaces the original answer only
     * when the judge finds strictly fewer unsupported claims (or flips to faithful).
     *
     * @param question user question; must not be blank
     * @return response carrying the answer, faithfulness verdict, validated citations,
     * any unsupported claims, and the full source audit trail
     */
    public ChatResponse chat(String question) {
        Embedding queryEmbedding = queryEmbeddingModel.embed(question).content();

        var retrieval = props.getRetrieval();
        List<EmbeddingMatch<TextSegment>> primary =
                search(queryEmbedding, retrieval.getMaxResults(), retrieval.getMinScore(), null);

        List<SourceItem> sources = new ArrayList<>();
        primary.forEach(m -> sources.add(toSource(m, false)));

        if (primary.size() < retrieval.getMinResults()) {
            log.info("Refusing question (only {} fragments above minScore)", primary.size());
            return ChatResponse.refusal(
                    "No sufficiently relevant information was found in your vault for this question.",
                    sources);
        }

        List<EmbeddingMatch<TextSegment>> linked = List.of();
        if (retrieval.isExpandLinkedContext()) {
            linked = expandLinkedContext(queryEmbedding, primary, sources);
        }

        List<TextSegment> sentFragments = new ArrayList<>();
        primary.forEach(m -> sentFragments.add(m.embedded()));
        linked.forEach(m -> sentFragments.add(m.embedded()));

        String context = buildContext(sentFragments);
        RagAnswer ragAnswer = assistant.answer(question, context);

        List<Citation> citations = faithfulness.validateCitations(ragAnswer, sentFragments);
        boolean faithful = true;
        List<String> unsupported = List.of();
        boolean regenerated = false;

        if (props.getFaithfulness().isVerify() && ragAnswer.isContextSufficient()) {
            FaithfulnessService.Verdict verdict = faithfulness.judge(ragAnswer.getAnswer(), sentFragments);
            faithful = verdict.faithful();
            unsupported = verdict.unsupportedClaims();

            if (!verdict.faithful() && !verdict.judgeError()
                    && props.getFaithfulness().isRegenerateIfUnfaithful()) {
                String retryQuestion = question
                        + "\n\nIMPORTANT: your previous answer contained unsupported claims: "
                        + unsupported + ". Answer again using ONLY the provided context.";
                RagAnswer retry = assistant.answer(retryQuestion, context);
                List<Citation> retryCitations = faithfulness.validateCitations(retry, sentFragments);
                FaithfulnessService.Verdict secondVerdict =
                        faithfulness.judge(retry.getAnswer(), sentFragments);
                if (secondVerdict.unsupportedClaims().size() < unsupported.size()
                        || (secondVerdict.faithful() && !verdict.faithful())) {
                    ragAnswer = retry;
                    citations = retryCitations;
                    faithful = secondVerdict.faithful();
                    unsupported = secondVerdict.unsupportedClaims();
                    regenerated = true;
                }
            }
        }

        return new ChatResponse(
                ragAnswer.getAnswer(),
                faithful,
                ragAnswer.isContextSufficient(),
                null,
                regenerated,
                citations.stream().map(CitationDto::from).toList(),
                unsupported,
                sources);
    }

    /**
     * Runs a raw similarity search without any LLM involvement.
     *
     * @param query    free-text query
     * @param limit    maximum number of hits; capped at 50; falls back to the configured
     *                 maxResults when null or non-positive
     * @param minScore similarity floor; falls back to the configured value when null
     * @param tag      optional tag filter (substring match against the tags_csv metadata)
     * @param notePath optional vault-relative path filter; takes precedence over {@code tag}
     * @return response with ranked fragments and snippets
     */
    public SearchResponse search(final String query, final Integer limit, final Double minScore,
                                 final String tag, final String notePath) {
        final int effectiveLimit = limit != null && limit > 0 ? Math.min(limit, 50)
                : props.getRetrieval().getMaxResults();
        final double effectiveMinScore = minScore != null ? minScore : props.getRetrieval().getMinScore();

        final Filter filter = buildFilter(tag, notePath);
        final Embedding queryEmbedding = queryEmbeddingModel.embed(query).content();
        final List<EmbeddingMatch<TextSegment>> matches =
                search(queryEmbedding, effectiveLimit, effectiveMinScore, filter);

        List<SourceItem> items = matches.stream()
                .map(embeddingMatch -> toSource(embeddingMatch, false))
                .toList();
        return new SearchResponse(query, items.size(), items);
    }

    /**
     * Builds the metadata filter for a search request.
     *
     * @param tag      optional tag filter
     * @param notePath optional path filter; wins over {@code tag} when both are given
     * @return a store filter, or null when neither parameter applies
     */
    private Filter buildFilter(String tag, String notePath) {
        if (StringUtils.isNotBlank(notePath)) {
            return MetadataFilterBuilder.metadataKey("file_path").isEqualTo(notePath);
        }
        if (StringUtils.isNotBlank(tag)) {
            return MetadataFilterBuilder.metadataKey("tags_csv").containsString(
                    tag.trim().toLowerCase());
        }
        return null;
    }

    /**
     * Pulls additional segments from notes that are strongly connected
     * (outgoing links / backlinks) to the hit notes.
     *
     * @param queryEmbedding embedded question, reused so linked notes are scored
     *                       against the same intent as the primary hits
     * @param primary        primary vector-search matches
     * @param sources        audit-trail list; expanded fragments are appended here
     *                       flagged as linked (mutated in place)
     * @return extra matches from linked notes (may be empty)
     */
    private List<EmbeddingMatch<TextSegment>> expandLinkedContext(Embedding queryEmbedding,
                                                                  List<EmbeddingMatch<TextSegment>> primary,
                                                                  List<SourceItem> sources) {
        Set<String> hitTitles = new LinkedHashSet<>();
        primary.forEach(m -> hitTitles.add(m.embedded().metadata().getString("note_title")));

        var retrieval = props.getRetrieval();
        List<String> candidates = graph.expansionCandidates(hitTitles, retrieval.getLinkedMaxNotes());

        List<EmbeddingMatch<TextSegment>> extra = new ArrayList<>();
        for (String candidate : candidates) {
            String path = graph.resolvePath(candidate);
            if (path == null) {
                continue;
            }
            Filter filter = MetadataFilterBuilder.metadataKey("file_path").isEqualTo(path);
            List<EmbeddingMatch<TextSegment>> found =
                    search(queryEmbedding, retrieval.getLinkedSegmentsPerNote(),
                            retrieval.getLinkedMinScore(), filter);
            found.forEach(m -> {
                extra.add(m);
                sources.add(toSource(m, true));
            });
        }
        return extra;
    }

    /**
     * Executes a vector search against the embedding store.
     *
     * @param queryEmbedding embedded query
     * @param maxResults     upper bound on returned matches
     * @param minScore       similarity threshold; matches below are dropped
     * @param filter         optional metadata filter; null disables filtering
     * @return matches ranked by descending score
     */
    private List<EmbeddingMatch<TextSegment>> search(final Embedding queryEmbedding, final int maxResults,
                                                     final double minScore, final Filter filter) {
        final EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();
        return store.search(request).matches();
    }

    /**
     * Concatenates fragments into the prompt context block, double-newline separated.
     *
     * @param fragments the exact fragments that will be sent to the model
     * @return plain-text context
     */
    private String buildContext(List<TextSegment> fragments) {
        StringBuilder sb = new StringBuilder();
        for (TextSegment fragment : fragments) {
            sb.append(fragment.text()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Maps an embedding match to its API source representation.
     *
     * @param match  store hit
     * @param linked true when the fragment originates from linked-context expansion
     *               rather than being a direct hit
     * @return source item with a truncated, single-line snippet; chunkIndex is -1
     * when the metadata key is missing
     */
    private SourceItem toSource(final EmbeddingMatch<TextSegment> match, final boolean linked) {
        final TextSegment segment = match.embedded();
        final Metadata md = segment.metadata();
        final String text = segment.text();
        final Integer chunkIndex = md.getInteger("chunk_index");
        return new SourceItem(
                md.getString("note_title"),
                md.getString("file_path"),
                chunkIndex == null ? -1 : chunkIndex,
                match.score(),
                linked,
                text.replace('\n', ' '));
    }
}
