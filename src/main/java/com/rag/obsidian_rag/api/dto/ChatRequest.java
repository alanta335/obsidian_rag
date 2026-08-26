package com.rag.obsidian_rag.api.dto;

/**
 * Request body for POST /api/chat.
 *
 * @param question user question; must not be blank
 */
public record ChatRequest(String question) {
}
