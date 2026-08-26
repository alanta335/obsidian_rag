package com.rag.obsidian_rag.api.dto;

import java.util.List;

/**
 * Response for GET /api/search.
 *
 * @param query   echoed query text
 * @param count   number of returned results
 * @param results ranked source fragments
 */
public record SearchResponse(String query, int count, List<SourceItem> results) {
}
