package com.rag.obsidian_rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code obsidian.rag.*} settings; every nested group
 * provides safe defaults so the app starts without explicit configuration.
 */
@Data
@ConfigurationProperties(prefix = "obsidian.rag")
public class VaultProperties {

    /** Absolute or relative path of the Obsidian vault to index. */
    private String vaultPath = "";

    /** Maximum characters per embedded chunk. */
    private int chunkSize = 1500;

    /** Character overlap between consecutive chunks of one section. */
    private int chunkOverlap = 200;

    /** Local JSON file holding per-note hashes for incremental indexing. */
    private String stateFile = "./data/index-state.json";

    private Nvidia nvidia = new Nvidia();
    private Retrieval retrieval = new Retrieval();
    private Faithfulness faithfulness = new Faithfulness();
    private Store store = new Store();

    /** NVIDIA NIM endpoint and model selection. */
    @Data
    public static class Nvidia {
        /** NVIDIA API key; resolved from {@code NVIDIA_API_KEY} (env var or .env). Blank fails fast at startup. */
        private String apiKey = "";
        private String baseUrl = "https://integrate.api.nvidia.com/v1";
        private String chatModel = "nvidia/nemotron-nano-3-30b-a3b";
        private String embeddingModel = "nvidia/nemotron-3-embed-1b";
        private int embeddingDimension = 2048;
        private int batchSize = 16;
        private double temperature = 0.2;
        private int maxTokens = 4096;
    }

    /** Vector-search tuning plus linked-context expansion knobs. */
    @Data
    public static class Retrieval {
        private int maxResults = 6;
        private double minScore = 0.55;
        private int minResults = 1;
        private boolean expandLinkedContext = true;
        private double linkedMinScore = 0.30;
        private int linkedMaxNotes = 3;
        private int linkedSegmentsPerNote = 2;
    }

    /** Gating for the entailment-judge layer. */
    @Data
    public static class Faithfulness {
        private boolean verify = true;
        private boolean regenerateIfUnfaithful = true;
    }

    /** PGVector connection settings; note the non-default port 5433. */
    @Data
    public static class Store {
        private String host = "localhost";
        private int port = 5433;
        private String database = "obsidian_rag";
        private String user = "postgres";
        private String password = "postgres";
        private String table = "obsidian_segments";
    }
}
