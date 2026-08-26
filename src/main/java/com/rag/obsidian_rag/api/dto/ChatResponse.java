package com.rag.obsidian_rag.api.dto;

import java.util.List;

/**
 * Grounded answer with faithfulness verdict and full source audit trail.
 */
public record ChatResponse(
        String answer,
        Boolean faithful,
        Boolean contextSufficient,
        String refusal,
        boolean regenerated,
        List<CitationDto> citations,
        List<String> unsupportedClaims,
        List<SourceItem> sources) {

    public static ChatResponse refusal(String message, List<SourceItem> sources) {
        return new ChatResponse(null, null, false, message, false, List.of(), List.of(), sources);
    }
}
