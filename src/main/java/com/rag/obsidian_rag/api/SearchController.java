package com.rag.obsidian_rag.api;

import com.rag.obsidian_rag.api.dto.SearchResponse;
import com.rag.obsidian_rag.rag.RagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final RagService ragService;

    /**
     * Creates the controller.
     *
     * @param ragService RAG orchestration service
     */
    public SearchController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Raw similarity-search endpoint (no LLM involved).
     *
     * @param q        query text (required)
     * @param limit    optional maximum number of results; capped server-side
     * @param minScore optional similarity floor overriding configuration
     * @param tag      optional tag filter
     * @param note     optional vault-relative note path filter
     * @return ranked fragments with snippets
     */
    @GetMapping
    public SearchResponse search(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Double minScore,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String note) {
        return ragService.search(q, limit, minScore, tag, note);
    }
}
