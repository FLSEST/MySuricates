package com.automation.service.pool;

import com.automation.config.WorkflowPoolConfig;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
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
import java.util.*;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowPoolService {

    private final WorkflowPoolConfig config;
    private final ObjectMapper objectMapper;
    private final WorkflowService workflowService;
    private final ProjectRepository projectRepository;

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

    public record InstallResult(Workflow workflow, boolean created) {}

    public InstallResult install(String poolId, InstallPoolWorkflowRequest request) {
        PoolWorkflowEntry entry = findById(poolId);

        String resolvedName = request.name() != null && !request.name().isBlank()
                ? request.name()
                : entry.name().getOrDefault("en", entry.name().values().iterator().next());
        String description = entry.description() != null ? entry.description().getOrDefault("en", "") : null;

        // Auto-inject project_path from Project entity into defaultContext
        Map<String, Object> mergedContext = entry.defaultContext() != null
                ? new java.util.HashMap<>(entry.defaultContext()) : new java.util.HashMap<>();
        projectRepository.findById(request.projectId()).ifPresent(project -> {
            if (project.getPath() != null && !project.getPath().isBlank()
                    && !mergedContext.containsKey("project_path")) {
                mergedContext.put("project_path", project.getPath());
            }
        });

        if (!Boolean.TRUE.equals(request.forceCreate())) {
            Optional<Workflow> existing = workflowService.findByNameAndProjectId(resolvedName, request.projectId());
            if (existing.isPresent()) {
                UpdateWorkflowRequest updateRequest = new UpdateWorkflowRequest(
                        resolvedName, description, entry.icon(), entry.blocks(),
                        entry.instructions(), entry.model(), null, entry.metadata(),
                        mergedContext, "Pool install: " + poolId
                );
                Workflow updated = workflowService.update(existing.get().getId(), updateRequest, ChangeSource.POOL_INSTALL);
                log.info("Updated pool workflow '{}' in-place: workflow '{}' ({})", poolId, updated.getName(), updated.getId());
                return new InstallResult(updated, false);
            }
        }

        CreateWorkflowRequest createRequest = new CreateWorkflowRequest(
                request.projectId(),
                resolvedName,
                description,
                entry.icon(),
                entry.blocks(),
                List.of(),
                entry.instructions(),
                entry.model(),
                null,
                null,
                entry.metadata(),
                mergedContext,
                entry.version()
        );

        Workflow workflow = workflowService.create(createRequest, ChangeSource.POOL_INSTALL);
        log.info("Installed pool workflow '{}' as new workflow '{}' ({})", poolId, workflow.getName(), workflow.getId());
        return new InstallResult(workflow, true);
    }

    public ExportToPoolResponse exportToPool(String workflowId, ExportToPoolRequest request) throws IOException {
        Workflow workflow = workflowService.findById(workflowId);
        if (request == null) {
            request = new ExportToPoolRequest(null, null, null, null, null, null, null);
        }

        String nameEn = request.nameEn() != null ? request.nameEn() : workflow.getName();
        String nameDe = request.nameDe() != null ? request.nameDe() : workflow.getName();
        String descEn = request.descriptionEn() != null ? request.descriptionEn() : workflow.getDescription();
        String descDe = request.descriptionDe() != null ? request.descriptionDe() : workflow.getDescription();

        // Resolve category from request, then metadata, then fallback
        String category = request.category();
        if (category == null && workflow.getMetadata() != null) {
            Object factoryCat = workflow.getMetadata().get("factoryCategory");
            if (factoryCat instanceof String s) category = s;
        }
        if (category == null) category = "CUSTOM";

        List<String> tags = request.tags() != null ? request.tags() : List.of();
        String author = request.author() != null ? request.author() : "MySuricates";

        // Build pool JSON in canonical order
        LinkedHashMap<String, Object> poolJson = new LinkedHashMap<>();
        poolJson.put("name", Map.of("de", nameDe, "en", nameEn));
        if (descEn != null || descDe != null) {
            poolJson.put("description", Map.of(
                    "de", descDe != null ? descDe : "",
                    "en", descEn != null ? descEn : ""));
        }
        poolJson.put("icon", workflow.getIcon() != null ? workflow.getIcon() : "\uD83D\uDD27");
        poolJson.put("category", category);
        if (!tags.isEmpty()) {
            poolJson.put("tags", tags);
        }
        poolJson.put("blocks", workflow.getBlocks());

        if (workflow.getInstructions() != null && !workflow.getInstructions().isBlank()) {
            poolJson.put("instructions", workflow.getInstructions());
        }
        if (workflow.getModel() != null) {
            poolJson.put("model", workflow.getModel());
        }
        poolJson.put("version", workflow.getVersion() != null ? workflow.getVersion() : "1.0.0");
        poolJson.put("author", author);

        if (workflow.getDefaultContext() != null && !workflow.getDefaultContext().isEmpty()) {
            poolJson.put("defaultContext", workflow.getDefaultContext());
        }

        // Filter out factory-internal metadata keys
        if (workflow.getMetadata() != null && !workflow.getMetadata().isEmpty()) {
            Map<String, Object> filtered = new LinkedHashMap<>(workflow.getMetadata());
            Set.of("factoryGenerated", "factoryTopic", "factoryCategory").forEach(filtered::remove);
            if (!filtered.isEmpty()) {
                poolJson.put("metadata", filtered);
            }
        }

        // Resolve filename with collision avoidance
        Path poolDir = resolvePoolDirectory();
        Files.createDirectories(poolDir);

        String slug = slugify(nameEn);
        String filename = slug + ".json";
        Path target = poolDir.resolve(filename);
        int suffix = 2;
        while (Files.exists(target)) {
            filename = slug + "-" + suffix + ".json";
            target = poolDir.resolve(filename);
            suffix++;
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), poolJson);
        String poolId = filename.replace(".json", "");
        log.info("Exported workflow '{}' ({}) to pool: {}", workflow.getName(), workflowId, filename);
        return new ExportToPoolResponse(poolId, filename);
    }

    private String slugify(String name) {
        String slug = name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            slug = "workflow-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return slug;
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

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = raw.containsKey("metadata")
                ? (Map<String, Object>) raw.get("metadata")
                : null;

        return new PoolWorkflowEntry(
                id, name, description, icon, category, tags,
                blocks, instructions, model, version, author,
                defaultContext, metadata, filename
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
