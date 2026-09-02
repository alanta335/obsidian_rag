package com.rag.obsidian_rag.rag;

import com.rag.obsidian_rag.api.dto.ChatResponse;
import com.rag.obsidian_rag.api.dto.CitationDto;
import com.rag.obsidian_rag.api.dto.SearchResponse;
import com.rag.obsidian_rag.api.dto.SourceItem;
import com.rag.obsidian_rag.config.VaultProperties;
import com.rag.obsidian_rag.graph.LinkGraphService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
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
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    private final ChatModel chatModel;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel queryEmbeddingModel;
    private final LinkGraphService graph;
    private final FaithfulnessService faithfulness;
    private final VaultProperties props;
    private final ObjectMapper mapper;

    /**
     * Creates the orchestration service.
     *
     * @param chatModel           chat model used for grounded generation
     * @param store               pgvector embedding store holding all indexed fragments
     * @param queryEmbeddingModel query-mode embedding model; must NOT be swapped for
     *                            the passage model (asymmetric NVIDIA embeddings)
     * @param graph               link graph used for linked-context expansion
     * @param faithfulness        citation validation and entailment judge
     * @param props               vault/retrieval/faithfulness configuration
     * @param mapper              Jackson mapper for parsing the model's JSON answer
     */
    public RagService(ChatModel chatModel,
                      EmbeddingStore<TextSegment> store,
                      @Qualifier("queryEmbeddingModel") EmbeddingModel queryEmbeddingModel,
                      LinkGraphService graph,
                      FaithfulnessService faithfulness,
                      VaultProperties props,
                      ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.store = store;
        this.queryEmbeddingModel = queryEmbeddingModel;
        this.graph = graph;
        this.faithfulness = faithfulness;
        this.props = props;
        this.mapper = mapper;
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
    public ChatResponse chat(final String question) {
        final long start = System.nanoTime();
        log.info("Chat request received: question='{}'", question);

        final Embedding queryEmbedding = queryEmbeddingModel.embed(question).content();
        log.info("Query embedded ({} ms)", elapsedMs(start));

        final var retrieval = props.getRetrieval();
        List<EmbeddingMatch<TextSegment>> primary =
                search(queryEmbedding, retrieval.getMaxResults(), retrieval.getMinScore(), null);
        log.info("Primary search returned {} fragments ({} ms)", primary.size(), elapsedMs(start));

        final List<SourceItem> sources = primary.stream().map(embeddingMatch ->
                toSource(embeddingMatch, false)).toList();

        if (primary.size() < retrieval.getMinResults()) {
            log.info("Refusing question (only {} fragments above minScore)", primary.size());
            return ChatResponse.refusal(
                    "No sufficiently relevant information was found in your vault for this question.",
                    sources);
        }

        List<EmbeddingMatch<TextSegment>> linked = List.of();
        if (retrieval.isExpandLinkedContext()) {
            linked = expandLinkedContext(queryEmbedding, primary, sources);
            log.info("Linked-context expansion added {} fragments ({} ms)", linked.size(), elapsedMs(start));
        }

        final List<TextSegment> sentFragments = Stream.concat(
                        primary.stream(),
                        linked.stream()
                ).map(EmbeddingMatch::embedded)
                .toList();
        final String context = buildContext(sentFragments);
        log.info("Calling chat model with {} fragments ({} ms elapsed)", sentFragments.size(), elapsedMs(start));
        RagAnswer ragAnswer = answer(question, context);
        log.info("Chat model answered ({} ms elapsed)", elapsedMs(start));

        List<Citation> citations = faithfulness.validateCitations(ragAnswer, sentFragments);
        log.info("Validated {} citations ({} ms elapsed)", citations.size(), elapsedMs(start));
        boolean faithful = true;
        List<String> unsupported = List.of();
        boolean regenerated = false;

        if (props.getFaithfulness().isVerify() && ragAnswer.isContextSufficient()) {
            log.info("Running faithfulness judge ({} ms elapsed)", elapsedMs(start));
            FaithfulnessService.Verdict verdict = faithfulness.judge(ragAnswer.getAnswer(), sentFragments);
            faithful = verdict.faithful();
            unsupported = verdict.unsupportedClaims();
            log.info("Faithfulness verdict: faithful={}, unsupported={} ({} ms elapsed)",
                    faithful, unsupported.size(), elapsedMs(start));

            if (!verdict.faithful() && !verdict.judgeError()
                    && props.getFaithfulness().isRegenerateIfUnfaithful()) {
                String retryQuestion = question
                        + "\n\nIMPORTANT: your previous answer contained unsupported claims: "
                        + unsupported + ". Answer again using ONLY the provided context.";
                log.info("Regenerating answer after unfaithful verdict ({} ms elapsed)", elapsedMs(start));
                RagAnswer retry = answer(retryQuestion, context);
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
                    log.info("Regenerated answer accepted: faithful={}, unsupported={} ({} ms elapsed)",
                            faithful, unsupported.size(), elapsedMs(start));
                } else {
                    log.info("Regenerated answer rejected (not better than original)");
                }
            }
        }

        log.info("Chat request completed in {} ms", elapsedMs(start));
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
    private List<EmbeddingMatch<TextSegment>> expandLinkedContext(final Embedding queryEmbedding,
                                                                  final List<EmbeddingMatch<TextSegment>> primary,
                                                                  final List<SourceItem> sources) {
        final Set<String> hitTitles = primary.stream().map(
                m -> m.embedded().metadata()
                        .getString("note_title")).collect(Collectors.toSet());

        final var retrieval = props.getRetrieval();
        final List<String> candidates = graph.expansionCandidates(hitTitles, retrieval.getLinkedMaxNotes());

        final List<EmbeddingMatch<TextSegment>> extra = new ArrayList<>();

        candidates.stream()
                .map(graph::resolvePath)
                .filter(Objects::nonNull)
                .flatMap(path -> {
                    final Filter filter =
                            MetadataFilterBuilder.metadataKey("file_path").isEqualTo(path);
                    return search(
                            queryEmbedding,
                            retrieval.getLinkedSegmentsPerNote(),
                            retrieval.getLinkedMinScore(),
                            filter
                    ).stream();
                })
                .forEach(embeddingMatch -> {
                    extra.add(embeddingMatch);
                    sources.add(toSource(embeddingMatch, true));
                });
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
     * Milliseconds elapsed since the given start timestamp.
     *
     * @param startNanos System.nanoTime() value taken at the start of the operation
     * @return elapsed time in milliseconds
     */
    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * Generates a structured answer via a plain chat call with a JSON-response
     * prompt. AiServices function-calling structured output is unreliable on the
     * NVIDIA endpoint (hangs or returns empty), so the model is asked for JSON
     * directly and parsed here, mirroring {@link FaithfulnessService#judge}.
     *
     * @param question user question
     * @param context  retrieved fragments
     * @return parsed answer; contextSufficient=false when the model output is not parseable
     */
    private RagAnswer answer(final String question, final String context) {
        String system = """
                You are a precise research assistant answering questions about the user's personal Obsidian vault.

                HARD RULES (non-negotiable):
                1. Base your answer ONLY on the fragments provided in <context>. Never use outside knowledge, assumptions, or speculation.
                2. If the context does not contain enough information to answer, set contextSufficient=false, leave answer=null and citations=[].
                3. Every claim you make MUST be backed by at least one entry in citations[].
                4. Each citation must reference the exact noteTitle and chunkIndex shown in the fragment header, include a VERBATIM quote copied from that fragment, and a short reasonForUse explaining why it supports the claim.
                5. Never invent notes, titles, chunk indices, quotes, or sources.

                Respond with ONLY a JSON object, no markdown fences and no extra text, in exactly this shape:
                {"answer": "your answer or null", "contextSufficient": true, "citations": [{"noteTitle": "...", "filePath": "...", "chunkIndex": 0, "quote": "verbatim quote", "reasonForUse": "..."}]}
                """;
        String user = "Question: " + question
                + "\n\nContext fragments from the vault:\n<context>\n" + context + "\n</context>";
        String raw = chatModel.chat(SystemMessage.from(system), UserMessage.from(user)).aiMessage().text();
        try {
            return mapper.readValue(stripFences(raw), RagAnswer.class);
        } catch (Exception e) {
            log.warn("Failed to parse model answer as JSON; treating context as insufficient: {}", e.getMessage());
            RagAnswer fallback = new RagAnswer();
            fallback.setContextSufficient(false);
            return fallback;
        }
    }

    /**
     * Removes wrapping markdown code fences some models add despite instructions.
     *
     * @param raw model response
     * @return bare JSON text ready for parsing
     */
    private static String stripFences(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int lastFence = s.lastIndexOf("```");
            if (lastFence >= 0) {
                s = s.substring(0, lastFence);
            }
        }
        return s.trim();
    }

    /**
     * Concatenates fragments into the prompt context block, double-newline separated.
     *
     * @param fragments the exact fragments that will be sent to the model
     * @return plain-text context
     */
    private String buildContext(final List<TextSegment> fragments) {
        final StringBuilder sb = new StringBuilder();
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
