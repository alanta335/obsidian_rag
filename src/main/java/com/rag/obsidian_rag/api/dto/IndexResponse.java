package com.rag.obsidian_rag.api.dto;

/**
 * Result of POST /api/index.
 *
 * @param totalNotes       notes found in the vault
 * @param indexedNotes     notes (re-)embedded during this call
 * @param skippedUnchanged notes skipped because their content hash matched
 * @param deletedNotes     notes purged from the store
 * @param chunksIndexed    segments embedded during this call
 * @param durationMs       wall-clock duration in milliseconds
 * @param status           execution status; "ok" on success
 */
public record IndexResponse(
        int totalNotes,
        int indexedNotes,
        int skippedUnchanged,
        int deletedNotes,
        int chunksIndexed,
        long durationMs,
        String status) {
}
