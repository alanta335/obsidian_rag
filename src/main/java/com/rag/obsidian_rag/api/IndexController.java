package com.rag.obsidian_rag.api;

import com.rag.obsidian_rag.api.dto.IndexResponse;
import com.rag.obsidian_rag.indexing.VaultIndexer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final VaultIndexer indexer;

    public IndexController(VaultIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Re-indexes the vault. Default is incremental (only new/changed/deleted
     * notes are processed); pass full=true to re-embed everything.
     */
    @PostMapping
    public IndexResponse index(@RequestParam(defaultValue = "false") boolean full) throws IOException {
        var stats = indexer.reindex(full);
        return new IndexResponse(stats.totalNotes(), stats.indexedNotes(), stats.skippedUnchanged(),
                stats.deletedNotes(), stats.chunksIndexed(), stats.durationMs(), "ok");
    }
}
