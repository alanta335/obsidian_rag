package com.rag.obsidian_rag.rag;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.rag.obsidian_rag.config.VaultProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Post-generation faithfulness layer:
 * <ol>
 *   <li>{@link #validateCitations}: every emitted citation must reference a
 *       fragment that was actually present in the prompt context (no
 *       fabricated sources).</li>
 *   <li>{@link #judge}: an LLM entailment pass verifying the answer's claims
 *       are supported by the fragments (config-gated).</li>
 * </ol>
 */
@Service
public class FaithfulnessService {

    private static final Logger log = LoggerFactory.getLogger(FaithfulnessService.class);
    private static final int QUOTE_PREVIEW_CHARS = 400;

    private final ChatModel chatModel;
    private final VaultProperties props;
    private final ObjectMapper mapper;

    /**
     * Creates the service.
     *
     * @param chatModel model used for the entailment-judge prompt
     * @param props     faithfulness configuration flags
     * @param mapper    Jackson 3 mapper for parsing the judge's JSON reply
     */
    public FaithfulnessService(ChatModel chatModel, VaultProperties props, ObjectMapper mapper) {
        this.chatModel = chatModel;
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * Outcome of the entailment judge.
     *
     * @param faithful          whether every claim in the answer is supported by the fragments
     * @param unsupportedClaims verbatim claims the judge could not ground
     * @param judgeError        true when the judge itself failed; {@code faithful} is
     *                          then optimistically true so answers are not lost
     */
    public record Verdict(boolean faithful, List<String> unsupportedClaims, boolean judgeError) {
    }

    /**
     * Keeps only citations whose (noteTitle, chunkIndex) pair exists in the fragments
     * actually sent to the model; duplicates collapse to the first occurrence.
     *
     * @param ragAnswer     structured model output (null-safe: yields an empty list)
     * @param sentFragments fragments present in the prompt context
     * @return citations that survived validation, in original order
     */
    public List<Citation> validateCitations(RagAnswer ragAnswer, List<TextSegment> sentFragments) {
        Set<String> validKeys = sentFragments.stream()
                .map(f -> key(titleOf(f), chunkIndexOf(f)))
                .collect(java.util.stream.Collectors.toSet());

        LinkedHashMap<String, Citation> kept = new LinkedHashMap<>();
        if (ragAnswer == null || ragAnswer.getCitations() == null) {
            return List.of();
        }
        for (Citation c : ragAnswer.getCitations()) {
            if (c == null || c.getNoteTitle() == null || c.getNoteTitle().isBlank()
                    || c.getChunkIndex() == null) {
                continue;
            }
            String k = key(c.getNoteTitle(), c.getChunkIndex());
            if (validKeys.contains(k)) {
                kept.putIfAbsent(k, c);
            } else {
                log.debug("Dropped fabricated citation {}#{}", c.getNoteTitle(), c.getChunkIndex());
            }
        }
        return new ArrayList<>(kept.values());
    }

    /**
     * LLM-as-judge: checks whether the answer is fully entailed by the
     * fragments. Optimistically returns faithful=true on judge failure.
     *
     * @param answer    generated answer text
     * @param fragments fragments that were sent as context
     * @return verdict with per-claim unsupported list; never throws
     */
    public Verdict judge(String answer, List<TextSegment> fragments) {
        String prompt = """
                You are a strict fact-checking judge. Determine whether the ANSWER below is fully \
                supported by the SOURCE FRAGMENTS from a personal notes vault.

                Respond with ONLY a JSON object, no markdown fences and no extra text:
                {"faithful": true, "unsupported_claims": []}

                A claim is unsupported unless some fragment explicitly entails it. List each \
                unsupported claim verbatim.

                SOURCE FRAGMENTS:
                %s

                ANSWER:
                %s""".formatted(renderFragments(fragments), answer);

        try {
            String raw = chatModel.chat(prompt);
            JsonNode node = mapper.readTree(stripFences(raw));
            boolean faithful = node.path("faithful").asBoolean(true);
            List<String> claims = new ArrayList<>();
            node.path("unsupported_claims").forEach(c -> claims.add(c.asText()));
            return new Verdict(faithful, claims, false);
        } catch (Exception e) {
            log.warn("Faithfulness judge failed; treating answer as faithful: {}", e.getMessage());
            return new Verdict(true, List.of(), true);
        }
    }

    /**
     * Renders numbered fragment previews (title › heading path, chunk index, capped
     * text) for judge and citation prompts.
     *
     * @param fragments fragments included in the prompt context
     * @return human-readable listing, one block per fragment
     */
    static String renderFragments(List<TextSegment> fragments) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (TextSegment f : fragments) {
            Metadata md = f.metadata();
            sb.append("[").append(i++).append("] ")
                    .append(titleOf(f));
            String hp = md.getString(MarkdownHeading.HEADING_PATH);
            if (hp != null && !hp.isBlank()) {
                sb.append(" › ").append(hp);
            }
            sb.append(" (chunk ").append(chunkIndexOf(f)).append(")\n")
                    .append(preview(f.text()))
                    .append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Flattens newlines and caps the text length for prompt previews.
     *
     * @param text fragment text
     * @return cleaned text of at most 400 chars, with an ellipsis suffix when truncated
     */
    private static String preview(String text) {
        String clean = text.replace('\n', ' ').trim();
        return clean.length() <= QUOTE_PREVIEW_CHARS ? clean
                : clean.substring(0, QUOTE_PREVIEW_CHARS) + "...";
    }

    /**
     * Removes wrapping markdown code fences some models add despite instructions.
     *
     * @param raw judge response
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
     * Reads the fragment's note title with a "?" fallback so rendering never shows null.
     *
     * @param f fragment
     * @return note_title metadata or "?"
     */
    private static String titleOf(TextSegment f) {
        String t = f.metadata().getString("note_title");
        return t == null ? "?" : t;
    }

    /**
     * Reads the fragment's chunk index with a -1 fallback for missing metadata.
     *
     * @param f fragment
     * @return chunk_index metadata or -1
     */
    private static Integer chunkIndexOf(TextSegment f) {
        Integer idx = f.metadata().getInteger("chunk_index");
        return idx == null ? -1 : idx;
    }

    /**
     * Builds the (title, chunk) identity used to compare citations against sent fragments.
     *
     * @param title      note title, trimmed and lower-cased
     * @param chunkIndex chunk index
     * @return identity key of the form {@code "title#chunk"}
     */
    private static String key(String title, Integer chunkIndex) {
        return title.trim().toLowerCase(Locale.ROOT) + "#" + chunkIndex;
    }

    /** Alias constants kept local to avoid a dependency on the parser package. */
    private static final class MarkdownHeading {
        static final String HEADING_PATH = "heading_path";
    }
}
