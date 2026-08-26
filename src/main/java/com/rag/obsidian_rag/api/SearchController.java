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

    public SearchController(RagService ragService) {
        this.ragService = ragService;
    }

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
