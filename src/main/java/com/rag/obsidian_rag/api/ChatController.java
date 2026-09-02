package com.rag.obsidian_rag.api;

import com.rag.obsidian_rag.api.dto.ChatRequest;
import com.rag.obsidian_rag.api.dto.ChatResponse;
import com.rag.obsidian_rag.rag.RagService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagService ragService;

    /**
     * Creates the controller.
     *
     * @param ragService RAG orchestration service
     */
    public ChatController(RagService ragService) {
        this.ragService = ragService;
    }

    /**
     * Answers a question against the vault.
     *
     * @param request body carrying the non-blank question
     * @return grounded answer with citations, faithfulness verdict and sources
     * @throws IllegalArgumentException when the question is missing or blank (HTTP 400)
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (StringUtils.isBlank(request.question())) {
            throw new IllegalArgumentException("Field 'question' is required and must not be blank");
        }
        log.info("POST /api/chat: question='{}'", request.question());
        return ragService.chat(request.question());
    }
}
