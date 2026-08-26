package com.rag.obsidian_rag.api;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.rag.obsidian_rag.api.dto.StatusResponse;
import com.rag.obsidian_rag.config.VaultProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/status")
public class StatusController {

    private final VaultProperties props;
    private final ObjectMapper mapper;

    public StatusController(VaultProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping
    public StatusResponse status() throws IOException {
        Path vault = Path.of(props.getVaultPath() == null ? "" : props.getVaultPath());

        int indexedNotes = 0;
        String lastIndexedAt = null;
        File stateFile = Path.of(props.getStateFile()).toFile();
        if (stateFile.exists()) {
            try {
                Map<String, Map<String, String>> state = mapper.readValue(stateFile,
                        new TypeReference<>() {
                        });
                indexedNotes = state.size();
                lastIndexedAt = state.values().stream()
                        .map(e -> e.get("indexedAt"))
                        .filter(v -> v != null)
                        .map(Instant::parse)
                        .max(Instant::compareTo)
                        .map(Instant::toString)
                        .orElse(null);
            } catch (Exception ignored) {
                // unreadable/empty state file: report zero
            }
        }

        return new StatusResponse(
                props.getVaultPath(),
                props.getVaultPath() != null && !props.getVaultPath().isBlank()
                        && Files.isDirectory(vault),
                indexedNotes,
                lastIndexedAt,
                props.getNvidia().getChatModel(),
                props.getNvidia().getEmbeddingModel(),
                props.getNvidia().getEmbeddingDimension(),
                props.getRetrieval().isExpandLinkedContext(),
                props.getFaithfulness().isVerify());
    }
}
