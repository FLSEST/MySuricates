package com.automation.service.pool;

import com.automation.config.WorkflowPoolConfig;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.workflow.WorkflowService;
import com.automation.util.LocalizedJson;
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
    private final PoolExportWriter exportWriter;

    public List<PoolWorkflowEntry> listAll() {
        return listAll(LocalizedJson.DEFAULT_LOCALE);
    }

    /** @param locale language of the blocks of bilingual pool files; name/description stay maps */
    public List<PoolWorkflowEntry> listAll(String locale) {
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
                    PoolWorkflowEntry entry = PoolFileParser.parse(file, objectMapper, locale);
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
        return findById(id, LocalizedJson.DEFAULT_LOCALE);
    }

    public PoolWorkflowEntry findById(String id, String locale) {
        return listAll(locale).stream()
                .filter(e -> e.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("PoolWorkflow", id));
    }

    public record InstallResult(Workflow workflow, boolean created) {}

    public InstallResult install(String poolId, InstallPoolWorkflowRequest request) {
        String locale = LocalizedJson.normalize(request.locale());
        PoolWorkflowEntry entry = findById(poolId, locale);

        String resolvedName = request.name() != null && !request.name().isBlank()
                ? request.name()
                : LocalizedJson.pick(entry.name(), locale);
        String description = entry.description() != null
                ? Objects.requireNonNullElse(LocalizedJson.pick(entry.description(), locale), "")
                : null;

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
            Optional<Workflow> existing = findInstalled(entry, resolvedName, request.projectId());
            if (existing.isPresent()) {
                UpdateWorkflowRequest updateRequest = new UpdateWorkflowRequest(
                        resolvedName, description, entry.icon(), entry.blocks(),
                        entry.instructions(), entry.model(), null, entry.metadata(),
                        mergedContext, "Pool install: " + poolId, null, null, entry.effort()
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
                entry.version(),
                entry.effort()
        );

        Workflow workflow = workflowService.create(createRequest, ChangeSource.POOL_INSTALL);
        log.info("Installed pool workflow '{}' as new workflow '{}' ({})", poolId, workflow.getName(), workflow.getId());
        return new InstallResult(workflow, true);
    }

    /**
     * The workflow an earlier install of {@code entry} left in the project — under the requested name
     * or under the name of another language, so switching languages updates it instead of adding a
     * second copy.
     */
    private Optional<Workflow> findInstalled(PoolWorkflowEntry entry, String resolvedName, String projectId) {
        Optional<Workflow> byName = workflowService.findByNameAndProjectId(resolvedName, projectId);
        if (byName.isPresent()) {
            return byName;
        }
        return entry.name().values().stream()
                .filter(name -> name != null && !name.equals(resolvedName))
                .distinct()
                .map(name -> workflowService.findByNameAndProjectId(name, projectId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Schreibt einen Workflow als Pool-Definition.
     *
     * @param scope {@code repo}, {@code portable} oder {@code external}; ohne Angabe gilt {@code portable}
     *              (siehe {@link PoolScope}). Der Wert ist ein Pool-Metadatum, kein Feld der
     *              Workflow-Definition.
     * @throws IllegalArgumentException bei einem unbekannten {@code scope}
     */
    public ExportToPoolResponse exportToPool(String workflowId, ExportToPoolRequest request, String scope)
            throws IOException {
        PoolScope poolScope = PoolScope.fromParam(scope);
        Workflow workflow = workflowService.findById(workflowId);
        return exportWriter.write(workflow, request, poolScope, resolvePoolDirectory());
    }

    private Path resolvePoolDirectory() {
        Path path = Paths.get(config.getDirectory());
        if (path.isAbsolute()) {
            return path;
        }
        return Paths.get(System.getProperty("user.dir")).resolve(path);
    }
}
