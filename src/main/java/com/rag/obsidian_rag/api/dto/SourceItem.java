package com.rag.obsidian_rag.api.dto;

/**
 * One retrieved fragment returned for auditing, independent of model citations.
 */
public record SourceItem(
        String noteTitle,
        String filePath,
        int chunkIndex,
        double score,
        boolean linked,
        String snippet) {
}
