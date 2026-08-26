package com.rag.obsidian_rag.indexing;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.rag.obsidian_rag.config.VaultProperties;
import com.rag.obsidian_rag.graph.LinkGraphService;
import com.rag.obsidian_rag.parser.MarkdownSectionSplitter;
import com.rag.obsidian_rag.parser.ObsidianDocumentParser;
import com.rag.obsidian_rag.parser.ObsidianNote;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Indexes the Obsidian vault into the PGVector embedding store.
 *
 * Incremental mode compares per-note SHA-256 hashes against a local state
 * file: unchanged notes are skipped, changed notes are removed (metadata
 * filter) and re-embedded, deleted notes are purged. Full mode re-embeds
 * everything.
 */
@Service
public class VaultIndexer {

    private static final Logger log = LoggerFactory.getLogger(VaultIndexer.class);

    private final VaultProperties props;
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel passageEmbeddingModel;
    private final LinkGraphService graph;
    private final ObsidianDocumentParser parser = new ObsidianDocumentParser();
    private final ObjectMapper mapper;

    public VaultIndexer(VaultProperties props,
                        EmbeddingStore<TextSegment> store,
                        @Qualifier("passageEmbeddingModel") EmbeddingModel passageEmbeddingModel,
                        LinkGraphService graph,
                        ObjectMapper mapper) {
        this.props = props;
        this.store = store;
        this.passageEmbeddingModel = passageEmbeddingModel;
        this.graph = graph;
        this.mapper = mapper;
    }

    public synchronized IndexStats reindex(boolean full) throws IOException {
        long start = System.currentTimeMillis();

        Path vault = Path.of(props.getVaultPath());
        if (props.getVaultPath().isBlank()) {
            throw new IllegalArgumentException("obsidian.rag.vault-path is not configured");
        }
        if (!Files.isDirectory(vault)) {
            throw new IllegalArgumentException(
                    "Vault path is not a directory: " + vault.toAbsolutePath());
        }

        Map<String, ObsidianNote> notes = loadNotes(vault);
        graph.rebuild(notes.values());

        Map<String, StateEntry> state = full ? new LinkedHashMap<>() : loadState();

        int deleted = purgeDeletedNotes(state, notes);

        int indexed = 0;
        int skipped = 0;
        int chunks = 0;

        for (ObsidianNote note : notes.values()) {
            StateEntry previous = state.get(note.relativePath());
            if (!full && previous != null && previous.hash().equals(note.contentHash())) {
                skipped++;
                continue;
            }
            store.removeAll(MetadataFilterBuilder
                    .metadataKey(MarkdownSectionSplitter.FILE_PATH)
                    .isEqualTo(note.relativePath()));

            List<TextSegment> segments =
                    MarkdownSectionSplitter.split(note, props.getChunkSize(), props.getChunkOverlap());
            embedAndAdd(segments);

            chunks += segments.size();
            indexed++;
            state.put(note.relativePath(),
                    new StateEntry(note.contentHash(), note.noteTitle(), Instant.now().toString()));
            log.debug("Indexed '{}' ({} chunks)", note.relativePath(), segments.size());
        }

        saveState(state);
        IndexStats stats = new IndexStats(notes.size(), indexed, skipped, deleted, chunks,
                System.currentTimeMillis() - start);
        log.info("Reindex complete: {}", stats);
        return stats;
    }

    private Map<String, ObsidianNote> loadNotes(Path vault) throws IOException {
        Map<String, ObsidianNote> notes = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(vault)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .filter(p -> !isHidden(vault, p))
                    .sorted(Comparator.comparing(p -> vault.relativize(p).toString()))
                    .forEach(path -> {
                        try {
                            ObsidianNote note = parser.parse(vault, path);
                            notes.put(note.relativePath(), note);
                        } catch (Exception e) {
                            log.warn("Failed to parse {}: {}", path, e.getMessage());
                        }
                    });
        }
        return notes;
    }

    private int purgeDeletedNotes(Map<String, StateEntry> state, Map<String, ObsidianNote> notes) {
        int deleted = 0;
        Iterator<Map.Entry<String, StateEntry>> it = state.entrySet().iterator();
        while (it.hasNext()) {
            String path = it.next().getKey();
            if (!notes.containsKey(path)) {
                store.removeAll(MetadataFilterBuilder
                        .metadataKey(MarkdownSectionSplitter.FILE_PATH)
                        .isEqualTo(path));
                it.remove();
                deleted++;
            }
        }
        return deleted;
    }

    private void embedAndAdd(List<TextSegment> segments) {
        int batchSize = Math.max(1, props.getNvidia().getBatchSize());
        for (int i = 0; i < segments.size(); i += batchSize) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + batchSize, segments.size()));
            List<Embedding> embeddings = passageEmbeddingModel.embedAll(batch).content();
            List<String> ids = batch.stream()
                    .map(s -> UUID.randomUUID().toString())
                    .toList();
            store.addAll(ids, embeddings, batch);
        }
    }

    private boolean isHidden(Path vault, Path file) {
        for (Path part : vault.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || name.equalsIgnoreCase(".obsidian") || name.equalsIgnoreCase(".trash")) {
                return true;
            }
        }
        return false;
    }

    private Path stateFile() throws IOException {
        Path file = Path.of(props.getStateFile());
        Files.createDirectories(file.toAbsolutePath().getParent());
        return file;
    }

    private Map<String, StateEntry> loadState() {
        try {
            Path file = stateFile();
            if (!Files.exists(file)) {
                return new LinkedHashMap<>();
            }
            return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, StateEntry>>() {
            });
        } catch (IOException e) {
            log.warn("Could not read index state file, starting fresh: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void saveState(Map<String, StateEntry> state) throws IOException {
        mapper.writeValue(stateFile().toFile(), state);
    }

    public record IndexStats(int totalNotes, int indexedNotes, int skippedUnchanged,
                             int deletedNotes, int chunksIndexed, long durationMs) {
    }

    public record StateEntry(String hash, String title, String indexedAt) {
    }
}
