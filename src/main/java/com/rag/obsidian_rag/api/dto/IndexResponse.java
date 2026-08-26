package com.rag.obsidian_rag.api.dto;

public record IndexResponse(
        int totalNotes,
        int indexedNotes,
        int skippedUnchanged,
        int deletedNotes,
        int chunksIndexed,
        long durationMs,
        String status) {
}
