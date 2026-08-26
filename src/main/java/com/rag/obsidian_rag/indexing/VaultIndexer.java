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

    /**
     * Creates the indexer.
     *
     * @param props                 configuration (vault path, chunking, batch size, state file)
     * @param store                 pgvector embedding store
     * @param passageEmbeddingModel passage-mode embedding model used for indexing
     * @param graph                 link graph rebuilt on every reindex run
     * @param mapper                Jackson 3 mapper for the JSON state file
     */
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

    /**
     * Re-indexes the vault. Incremental mode skips notes whose SHA-256 matches the
     * recorded hash, deletes stale vectors of changed/deleted notes before
     * re-embedding, and persists the updated state afterwards. Full mode ignores
     * prior state and re-embeds everything. The link graph is rebuilt even when no
     * note changes; files that fail to parse are logged and skipped.
     *
     * @param full true to bypass hashes and re-embed every note
     * @return statistics describing this run
     * @throws IllegalArgumentException when the vault path is unset or not a directory
     * @throws IOException             when the state file cannot be written
     */
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

    /**
     * Walks the vault and parses every visible .md file (hidden folders such as
     * .obsidian/.trash are excluded). Unparsable files log a warning and are omitted.
     *
     * @param vault vault root directory
     * @return notes keyed by relative path, in deterministic walk order
     * @throws IOException when walking the tree fails
     */
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

    /**
     * Removes vectors and state entries for notes that no longer exist on disk.
     *
     * @param state mutable incremental-index state (mutated: deleted entries removed)
     * @param notes notes currently on disk keyed by relative path
     * @return number of purged notes
     */
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

    /**
     * Embeds segments in configured-size batches and inserts them with fresh UUIDs.
     *
     * @param segments chunks of a single note
     */
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

    /**
     * Checks whether any path component between the vault root and the file marks
     * it hidden (dot-prefixed names, .obsidian, .trash).
     *
     * @param vault vault root
     * @param file  candidate file below the root
     * @return true when the file lives under a hidden component
     */
    private boolean isHidden(Path vault, Path file) {
        for (Path part : vault.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || name.equalsIgnoreCase(".obsidian") || name.equalsIgnoreCase(".trash")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the state-file location, creating parent directories on demand.
     *
     * @return path of the incremental-index state file
     * @throws IOException when parent directories cannot be created
     */
    private Path stateFile() throws IOException {
        Path file = Path.of(props.getStateFile());
        Files.createDirectories(file.toAbsolutePath().getParent());
        return file;
    }

    /**
     * Loads the persisted per-note hashes. An unreadable state degrades to an
     * empty map (forcing re-embedding) instead of failing the run.
     *
     * @return mutable state map keyed by vault-relative path
     */
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

    /**
     * Persists the incremental-index state for restart recovery.
     *
     * @param state state to persist
     * @throws IOException when writing fails
     */
    private void saveState(Map<String, StateEntry> state) throws IOException {
        mapper.writeValue(stateFile().toFile(), state);
    }

    /**
     * Summary counters for one reindex run.
     *
     * @param totalNotes       notes found on disk
     * @param indexedNotes     notes (re-)embedded during the run
     * @param skippedUnchanged notes skipped because their hash matched
     * @param deletedNotes     notes purged because they no longer exist
     * @param chunksIndexed    segments embedded during the run
     * @param durationMs       wall-clock duration of the whole run in milliseconds
     */
    public record IndexStats(int totalNotes, int indexedNotes, int skippedUnchanged,
                             int deletedNotes, int chunksIndexed, long durationMs) {
    }

    /**
     * Persisted per-note index state.
     *
     * @param hash      SHA-256 of the raw file bytes at index time
     * @param title     note title at index time
     * @param indexedAt ISO-8601 instant of the last indexing
     */
    public record StateEntry(String hash, String title, String indexedAt) {
    }
}
