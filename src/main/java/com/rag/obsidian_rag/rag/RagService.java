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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int SNIPPET_CHARS = 200;

    private final Assistant assistant;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel queryEmbeddingModel;
    private final LinkGraphService graph;
    private final FaithfulnessService faithfulness;
    private final VaultProperties props;

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

    public SearchResponse search(String query, Integer limit, Double minScore, String tag, String notePath) {
        int effectiveLimit = limit != null && limit > 0 ? Math.min(limit, 50)
                : props.getRetrieval().getMaxResults();
        double effectiveMinScore = minScore != null ? minScore : props.getRetrieval().getMinScore();

        Filter filter = buildFilter(tag, notePath);
        Embedding queryEmbedding = queryEmbeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> matches =
                search(queryEmbedding, effectiveLimit, effectiveMinScore, filter);

        List<SourceItem> items = matches.stream()
                .map(m -> toSource(m, false))
                .toList();
        return new SearchResponse(query, items.size(), items);
    }

    private Filter buildFilter(String tag, String notePath) {
        if (notePath != null && !notePath.isBlank()) {
            return MetadataFilterBuilder.metadataKey("file_path").isEqualTo(notePath);
        }
        if (tag != null && !tag.isBlank()) {
            return MetadataFilterBuilder.metadataKey("tags_csv").containsString(
                    tag.trim().toLowerCase());
        }
        return null;
    }

    /**
     * Pulls additional segments from notes that are strongly connected
     * (outgoing links / backlinks) to the hit notes.
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

    private List<EmbeddingMatch<TextSegment>> search(Embedding queryEmbedding, int maxResults,
                                                     double minScore, Filter filter) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();
        return store.search(request).matches();
    }

    private String buildContext(List<TextSegment> fragments) {
        StringBuilder sb = new StringBuilder();
        for (TextSegment fragment : fragments) {
            sb.append(fragment.text()).append("\n\n");
        }
        return sb.toString();
    }

    private SourceItem toSource(EmbeddingMatch<TextSegment> match, boolean linked) {
        TextSegment segment = match.embedded();
        Metadata md = segment.metadata();
        String text = segment.text();
        String snippet = text.length() <= SNIPPET_CHARS ? text : text.substring(0, SNIPPET_CHARS) + "...";
        Integer chunkIndex = md.getInteger("chunk_index");
        return new SourceItem(
                md.getString("note_title"),
                md.getString("file_path"),
                chunkIndex == null ? -1 : chunkIndex,
                match.score(),
                linked,
                snippet.replace('\n', ' '));
    }
}
