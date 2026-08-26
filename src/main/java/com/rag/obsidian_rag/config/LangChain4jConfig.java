package com.rag.obsidian_rag.config;

import com.rag.obsidian_rag.rag.Assistant;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * All AI components target the NVIDIA NIM endpoint
 * (https://integrate.api.nvidia.com), which implements the OpenAI-compatible
 * API. No models run locally.
 *
 * The nv-embedqa embedding models are asymmetric: they require input_type=passage
 * when indexing documents and input_type=query when searching. Two separate
 * embedding model beans enforce this distinction.
 */
@Configuration
public class LangChain4jConfig {

    @Bean
    public ChatModel chatModel(VaultProperties props) {
        var n = props.getNvidia();
        return OpenAiChatModel.builder()
                .baseUrl(n.getBaseUrl())
                .apiKey(requiredApiKey())
                .modelName(n.getChatModel())
                .temperature(n.getTemperature())
                .maxTokens(n.getMaxTokens())
                .timeout(Duration.ofSeconds(180))
                .maxRetries(2)
                .build();
    }

    /** Passage-mode embeddings for indexing vault notes. */
    @Bean
    public EmbeddingModel passageEmbeddingModel(VaultProperties props) {
        return embeddingModel(props, "passage");
    }

    /** Query-mode embeddings for retrieval-time questions. */
    @Bean
    public EmbeddingModel queryEmbeddingModel(VaultProperties props) {
        return embeddingModel(props, "query");
    }

    @Bean
    public EmbeddingStore<dev.langchain4j.data.segment.TextSegment> embeddingStore(VaultProperties props) {
        var s = props.getStore();
        return PgVectorEmbeddingStore.builder()
                .host(s.getHost())
                .port(s.getPort())
                .database(s.getDatabase())
                .user(s.getUser())
                .password(s.getPassword())
                .table(s.getTable())
                .dimension(props.getNvidia().getEmbeddingDimension())
                .createTable(true)
                .dropTableFirst(false)
                .build();
    }

    @Bean
    public Assistant assistant(ChatModel chatModel) {
        return AiServices.create(Assistant.class, chatModel);
    }

    private OpenAiEmbeddingModel embeddingModel(VaultProperties props, String inputType) {
        var n = props.getNvidia();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(n.getBaseUrl())
                .apiKey(requiredApiKey())
                .modelName(n.getEmbeddingModel())
                .customParameters(Map.of("input_type", inputType))
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private String requiredApiKey() {
        String key = System.getenv("NVIDIA_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "Environment variable NVIDIA_API_KEY is not set. "
                            + "Create a free key at https://build.nvidia.com/settings");
        }
        return key;
    }
}
