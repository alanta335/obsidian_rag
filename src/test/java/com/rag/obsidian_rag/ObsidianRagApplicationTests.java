package com.rag.obsidian_rag;

import com.rag.obsidian_rag.config.VaultProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies relaxed-binding of obsidian.rag.* properties without booting the
 * full application context (which requires NVIDIA_API_KEY and a live Postgres).
 */
class ObsidianRagApplicationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Configuration
    @EnableConfigurationProperties(VaultProperties.class)
    static class BindingConfig {
    }

    @Test
    void defaultsAreApplied() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(VaultProperties.class);
            VaultProperties props = ctx.getBean(VaultProperties.class);
            assertThat(props.getChunkSize()).isEqualTo(1500);
            assertThat(props.getChunkOverlap()).isEqualTo(200);
            assertThat(props.getNvidia().getBaseUrl()).isEqualTo("https://integrate.api.nvidia.com/v1");
            assertThat(props.getNvidia().getEmbeddingModel()).isEqualTo("nvidia/nv-embedqa-e5-v5");
            assertThat(props.getNvidia().getEmbeddingDimension()).isEqualTo(1024);
            assertThat(props.getRetrieval().getMinScore()).isEqualTo(0.55);
            assertThat(props.getFaithfulness().isVerify()).isTrue();
            assertThat(props.getStore().getPort()).isEqualTo(5433);
        });
    }

    @Test
    void yamlStyleKeysAreBound() {
        runner.withPropertyValues(
                "obsidian.rag.vault-path=D:/Notes/MyVault",
                "obsidian.rag.chunk-size=999",
                "obsidian.rag.nvidia.chat-model=custom/model-1",
                "obsidian.rag.retrieval.max-results=12",
                "obsidian.rag.faithfulness.verify=false",
                "obsidian.rag.store.table=custom_segments"
        ).run(ctx -> {
            VaultProperties props = ctx.getBean(VaultProperties.class);
            assertThat(props.getVaultPath()).isEqualTo("D:/Notes/MyVault");
            assertThat(props.getChunkSize()).isEqualTo(999);
            assertThat(props.getNvidia().getChatModel()).isEqualTo("custom/model-1");
            assertThat(props.getRetrieval().getMaxResults()).isEqualTo(12);
            assertThat(props.getFaithfulness().isVerify()).isFalse();
            assertThat(props.getStore().getTable()).isEqualTo("custom_segments");
        });
    }
}
