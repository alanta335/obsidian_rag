package com.rag.obsidian_rag.api;

import com.rag.obsidian_rag.api.dto.ChatRequest;
import com.rag.obsidian_rag.api.dto.ChatResponse;
import com.rag.obsidian_rag.rag.RagService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService ragService;

    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Field 'question' is required and must not be blank");
        }
        return ragService.chat(request.question());
    }
}
