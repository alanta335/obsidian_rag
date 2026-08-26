package com.rag.obsidian_rag.api.dto;

import java.util.List;

public record SearchResponse(String query, int count, List<SourceItem> results) {
}
