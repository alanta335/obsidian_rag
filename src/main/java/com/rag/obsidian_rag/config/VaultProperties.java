package com.rag.obsidian_rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "obsidian.rag")
public class VaultProperties {

    private String vaultPath = "";
    private int chunkSize = 1500;
    private int chunkOverlap = 200;
    private String stateFile = "./data/index-state.json";

    private Nvidia nvidia = new Nvidia();
    private Retrieval retrieval = new Retrieval();
    private Faithfulness faithfulness = new Faithfulness();
    private Store store = new Store();

    @Data
    public static class Nvidia {
        private String baseUrl = "https://integrate.api.nvidia.com/v1";
        private String chatModel = "deepseek-ai/deepseek-v4-flash-0731";
        private String embeddingModel = "nvidia/nv-embedqa-e5-v5";
        private int embeddingDimension = 1024;
        private int batchSize = 16;
        private double temperature = 0.2;
        private int maxTokens = 4096;
    }

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

    @Data
    public static class Faithfulness {
        private boolean verify = true;
        private boolean regenerateIfUnfaithful = true;
    }

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
