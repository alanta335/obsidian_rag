package com.rag.obsidian_rag.api.dto;

public record StatusResponse(
        String vaultPath,
        boolean vaultExists,
        int indexedNotes,
        String lastIndexedAt,
        String chatModel,
        String embeddingModel,
        int embeddingDimension,
        boolean linkedContextEnabled,
        boolean faithfulnessVerify) {
}
