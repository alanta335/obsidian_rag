package com.rag.obsidian_rag.api.dto;

/**
 * Configuration and indexing-progress snapshot for GET /api/status.
 *
 * @param vaultPath            configured vault path (may be blank when unset)
 * @param vaultExists          whether the configured path is an existing directory
 * @param indexedNotes         number of entries in the local index-state file
 * @param lastIndexedAt        newest indexedAt timestamp from the state file (ISO-8601); null when unknown
 * @param chatModel            NVIDIA chat model id
 * @param embeddingModel       NVIDIA embedding model id
 * @param embeddingDimension   embedding vector dimension
 * @param linkedContextEnabled whether linked-context expansion is enabled
 * @param faithfulnessVerify   whether the entailment judge runs
 */
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
