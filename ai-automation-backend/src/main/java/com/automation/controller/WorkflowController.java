package com.automation.controller;

import com.automation.dto.request.ActivateSkillsRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.ExecuteWorkflowRequest;
import com.automation.dto.request.ImportWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.ExportWorkflowResponse;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.ChangeSource;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.project.ProjectService;
import com.automation.service.export.ProcessFormatAdapter;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.DefinitionIntegrity;
import com.automation.service.workflow.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Workflows: create, read, change and delete them, start runs, list their runs and move them as JSON. */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final ExecutionQueryService executionQueryService;
    private final ProjectService projectService;
    private final WorkflowMapper workflowMapper;
    private final ProcessFormatAdapter bpmnExportAdapter;

    /** Lists all workflows of projects that are not archived, each with its run count. */
    @GetMapping
    public List<WorkflowResponse> listWorkflows() {
        return workflowMapper.toResponseListWithRunCounts(workflowService.findAll());
    }

    /**
     * Creates a workflow in the project named by {@code projectId} and answers {@code 201}.
     * The first version row is written with it; unknown block types are rejected.
     */
    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        var workflow = workflowService.create(request, ChangeSource.USER);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

    /** Returns one workflow with its blocks and activated skills. */
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable String id) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.findById(id)));
    }

    /**
     * Changes the given fields of a workflow; fields left out stay as they are.
     * A definition change writes a new version and is refused on a sealed workflow ({@code 409 WORKFLOW_SEALED});
     * name and {@code version} label are no definition change and stay editable.
     */
    @PutMapping("/{id}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(@PathVariable String id, @RequestBody UpdateWorkflowRequest request) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.update(id, request, ChangeSource.USER)));
    }

    /** Soft-deletes a workflow ({@code 204}); a sealed workflow is refused with {@code 409 WORKFLOW_SEALED}. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable String id) {
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Starts a manual run and returns it right away with status {@code RUNNING}; the blocks run in the background.
     * The optional {@code context} is merged over the workflow's default context. A refused start creates no run.
     */
    @PostMapping("/{id}/execute")
    public ResponseEntity<WorkflowRunResponse> executeWorkflow(
            @PathVariable String id,
            @RequestBody(required = false) ExecuteWorkflowRequest request) {

        Workflow workflow = workflowService.findByIdEager(id);
        Map<String, Object> context = request != null ? request.context() : Map.of();

        WorkflowRun run = workflowExecutor.execute(workflow, context, TriggerSource.MANUAL);

        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    /** Lists the workflow's runs, newest first, paginated ({@code page}, {@code size}) and without their steps. */
    @GetMapping("/{id}/runs")
    public PageResponse<WorkflowRunListItemResponse> getWorkflowRuns(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return executionQueryService.listRunsForWorkflow(id, page, size);
    }

    /** Returns one run with its steps; the run is looked up by {@code runId} alone. */
    @GetMapping("/{id}/runs/{runId}")
    public WorkflowRunResponse getWorkflowRun(@PathVariable String id, @PathVariable String runId) {
        return executionQueryService.getRunForWorkflow(runId);
    }

    /** Activates the skills in {@code skillIds} for the workflow; already active ones are kept once. */
    @PostMapping("/{id}/skills")
    @RequiresModule(ModuleCatalog.SKILLS)
    public ResponseEntity<WorkflowResponse> activateSkills(
            @PathVariable String id,
            @Valid @RequestBody ActivateSkillsRequest request) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.activateSkills(id, request.skillIds())));
    }

    /** Deactivates one skill for the workflow. */
    @DeleteMapping("/{id}/skills/{skillId}")
    @RequiresModule(ModuleCatalog.SKILLS)
    public ResponseEntity<WorkflowResponse> deactivateSkill(@PathVariable String id, @PathVariable String skillId) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.deactivateSkill(id, skillId)));
    }

    /**
     * Exports the workflow as portable JSON without ids or project, with an {@code integrity} checksum
     * that {@code POST /api/v1/workflows/import} verifies.
     */
    @GetMapping("/export/{id}")
    public ExportWorkflowResponse exportWorkflow(@PathVariable String id) {
        var workflow = workflowService.findById(id);
        var export = new ExportWorkflowResponse(
                workflow.getName(),
                workflow.getDescription(),
                workflow.getIcon(),
                workflow.getBlocks(),
                workflow.getInstructions(),
                workflow.getModel(),
                workflow.getVersion(),
                workflow.getDefaultContext(),
                workflow.getMetadata(),
                Instant.now()
        );
        return export.withIntegrity(DefinitionIntegrity.ofExport(export));
    }

    /** Exportiert den Workflow als BPMN 2.0 XML (Spec: docs/plans/2026-07-09-bpmn-integration-design.md). */
    @GetMapping("/{id}/export/bpmn")
    @RequiresModule(ModuleCatalog.BPMN)
    public ResponseEntity<String> exportWorkflowAsBpmn(@PathVariable String id) {
        Workflow workflow = workflowService.findByIdEager(id);
        String xml = bpmnExportAdapter.export(workflow);
        String safeName = workflow.getName().replaceAll("[^a-zA-Z0-9_-]", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeName + "." + bpmnExportAdapter.fileExtension() + "\"")
                .contentType(MediaType.parseMediaType(bpmnExportAdapter.contentType()))
                .body(xml);
    }

    /**
     * Imports a workflow from its JSON export and answers {@code 201}.
     * With {@code updateIfExists} a workflow of the same name in the target project is updated instead
     * ({@code 200}); without {@code projectId} it goes to the collection project. A checksum in {@code integrity}
     * that does not match the content is rejected with {@code 400} before anything is written.
     */
    @PostMapping("/import")
    public ResponseEntity<WorkflowResponse> importWorkflow(@Valid @RequestBody ImportWorkflowRequest request) {
        DefinitionIntegrity.verifyImport(request); // mismatch → 400 before anything is written (TODO b18939a6)
        String projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            projectId = projectService.getOrCreateCollectionProject().getId();
        }

        // Blank keeps the label, as on create and on a pool reinstall — an export file is no PUT (TODO 5e2e73a5)
        String version = request.version() == null || request.version().isBlank() ? null : request.version();

        // Check for existing workflow if updateIfExists is true
        if (Boolean.TRUE.equals(request.updateIfExists())) {
            var existing = workflowService.findByNameAndProjectId(request.name(), projectId);
            if (existing.isPresent()) {
                var updateRequest = new UpdateWorkflowRequest(
                        request.name(),
                        request.description(),
                        request.icon(),
                        request.blocks(),
                        request.instructions(),
                        request.model(),
                        null,
                        // Like on create; metadata merges as on PUT (TODO 3e405c14)
                        request.metadata(),
                        request.defaultContext(),
                        "Imported workflow update",
                        null,
                        null,
                        null,
                        version
                );
                var updated = workflowService.update(existing.get().getId(), updateRequest, ChangeSource.IMPORT);
                return ResponseEntity.ok(workflowMapper.toResponse(updated));
            }
        }

        var createRequest = new CreateWorkflowRequest(
                projectId,
                request.name(),
                request.description(),
                request.icon(),
                request.blocks(),
                List.of(),
                request.instructions(),
                request.model(),
                null,
                null,
                request.metadata(),
                request.defaultContext(),
                version
        );
        var workflow = workflowService.create(createRequest, ChangeSource.IMPORT);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

}
