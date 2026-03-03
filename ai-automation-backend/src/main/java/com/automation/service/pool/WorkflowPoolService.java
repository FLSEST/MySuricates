package com.automation.service.pool;

import com.automation.config.WorkflowPoolConfig;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowPoolService {

    private final WorkflowPoolConfig config;
    private final ObjectMapper objectMapper;
    private final WorkflowService workflowService;

    public List<PoolWorkflowEntry> listAll() {
        if (!config.isEnabled()) {
            return Collections.emptyList();
        }

        Path poolDir = resolvePoolDirectory();
        if (!Files.isDirectory(poolDir)) {
            log.debug("Pool directory does not exist: {}", poolDir);
            return Collections.emptyList();
        }

        List<PoolWorkflowEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(poolDir, "*.json")) {
            for (Path file : stream) {
                try {
                    PoolWorkflowEntry entry = parsePoolFile(file);
                    if (entry != null) {
                        entries.add(entry);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse pool workflow file: {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan pool directory: {}", poolDir, e);
        }

        entries.sort((a, b) -> {
            String nameA = a.name().getOrDefault("en", "");
            String nameB = b.name().getOrDefault("en", "");
            return nameA.compareToIgnoreCase(nameB);
        });

        return entries;
    }

    public PoolWorkflowEntry findById(String id) {
        return listAll().stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("PoolWorkflow", id));
    }

    public Workflow install(String poolId, InstallPoolWorkflowRequest request) {
        PoolWorkflowEntry entry = findById(poolId);

        String resolvedName = request.name() != null && !request.name().isBlank()
                ? request.name()
                : entry.name().getOrDefault("en", entry.name().values().iterator().next());

        CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
                request.projectId(),
                resolvedName,
                entry.description() != null ? entry.description().getOrDefault("en", "") : null,
                entry.icon(),
                entry.blocks(),
                List.of(),
                entry.instructions(),
                entry.model(),
                null,
                null,
                null,
                entry.defaultContext()
        );

        Workflow workflow = workflowService.create(createRequest);
        log.info("Installed pool workflow '{}' as workflow '{}' ({})", poolId, workflow.getName(), workflow.getId());
        return workflow;
    }

    private PoolWorkflowEntry parsePoolFile(Path file) throws IOException {
        Map<String, Object> raw = objectMapper.readValue(file.toFile(), new TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, String> name = (Map<String, String>) raw.get("name");
        if (name == null || name.isEmpty()) {
            log.warn("Pool file missing required 'name' field: {}", file.getFileName());
            return null;
        }

        Object blocksRaw = raw.get("blocks");
        if (blocksRaw == null) {
            log.warn("Pool file missing required 'blocks' field: {}", file.getFileName());
            return null;
        }
        List<BlockDefinition> blocks = objectMapper.convertValue(blocksRaw, new TypeReference<>() {});

        String filename = file.getFileName().toString();
        String id = filename.replace(".json", "");

        @SuppressWarnings("unchecked")
        Map<String, String> description = raw.containsKey("description")
                ? (Map<String, String>) raw.get("description")
                : Map.of();

        String icon = (String) raw.getOrDefault("icon", "📋");
        String category = (String) raw.get("category");

        @SuppressWarnings("unchecked")
        List<String> tags = raw.containsKey("tags")
                ? (List<String>) raw.get("tags")
                : List.of();

        String instructions = (String) raw.get("instructions");
        String model = (String) raw.get("model");
        String version = (String) raw.getOrDefault("version", "1.0.0");
        String author = (String) raw.get("author");

        @SuppressWarnings("unchecked")
        Map<String, Object> defaultContext = raw.containsKey("defaultContext")
                ? (Map<String, Object>) raw.get("defaultContext")
                : null;

        return new PoolWorkflowEntry(
                id, name, description, icon, category, tags,
                blocks, instructions, model, version, author,
                defaultContext, filename
        );
    }

    private Path resolvePoolDirectory() {
        Path path = Paths.get(config.getDirectory());
        if (path.isAbsolute()) {
            return path;
        }
        return Paths.get(System.getProperty("user.dir")).resolve(path);
    }
}
