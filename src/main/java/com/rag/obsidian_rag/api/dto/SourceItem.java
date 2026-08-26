package com.rag.obsidian_rag.api.dto;

/**
 * One retrieved fragment returned for auditing, independent of model citations.
 *
 * @param noteTitle  title of the note the fragment belongs to
 * @param filePath   vault-relative path of the note
 * @param chunkIndex zero-based chunk position; -1 when unknown
 * @param score      similarity score of the match
 * @param linked     true when pulled in via linked-context expansion
 * @param snippet    truncated single-line preview of the fragment text
 */
public record SourceItem(
        String noteTitle,
        String filePath,
        int chunkIndex,
        double score,
        boolean linked,
        String snippet) {
}
