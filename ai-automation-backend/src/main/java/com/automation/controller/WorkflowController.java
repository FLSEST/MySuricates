package com.automation.controller;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.request.ActivateSkillsRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.ExecuteWorkflowRequest;
import com.automation.dto.request.ImportWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.ExportWorkflowResponse;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.WorkflowVersionResponse;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.entity.WorkflowVersion;
import com.automation.mapper.WorkflowMapper;
import com.automation.mapper.WorkflowVersionMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ProjectService;
import com.automation.entity.RunStatus;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowOptimizationService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final ProjectService projectService;
    private final WorkflowMapper workflowMapper;
    private final WorkflowOptimizationService optimizationService;
    private final AutoRepairService autoRepairService;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<WorkflowResponse> listWorkflows() {
        return workflowMapper.toResponseList(workflowService.findAll());
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        var workflow = workflowService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowResponse> getWorkflow(@PathVariable String id) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.findById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorkflowResponse> updateWorkflow(@PathVariable String id, @RequestBody UpdateWorkflowRequest request) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkflow(@PathVariable String id) {
        workflowService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<WorkflowRunResponse> executeWorkflow(
            @PathVariable String id,
            @RequestBody(required = false) ExecuteWorkflowRequest request) {

        Workflow workflow = workflowService.findByIdEager(id);
        Map<String, Object> context = request != null ? request.context() : Map.of();

        WorkflowRun run = workflowExecutor.execute(workflow, context);

        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    @GetMapping("/{id}/runs")
    @Transactional(readOnly = true)
    public PageResponse<WorkflowRunResponse> getWorkflowRuns(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var result = runRepository.findByWorkflowIdOrderByStartedAtDesc(id, PageRequest.of(page, size));
        return new PageResponse<>(
                workflowMapper.toRunResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @GetMapping("/{id}/runs/{runId}")
    @Transactional(readOnly = true)
    public WorkflowRunResponse getWorkflowRun(@PathVariable String id, @PathVariable String runId) {
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new com.automation.exception.ResourceNotFoundException("WorkflowRun", runId));
        return workflowMapper.toRunResponse(run);
    }

    @PostMapping("/{id}/optimize")
    public ResponseEntity<Map<String, Object>> optimizeWorkflow(@PathVariable String id) {
        Map<String, Object> suggestion = optimizationService.optimizeWorkflow(id);
        return ResponseEntity.ok(suggestion);
    }

    @PostMapping("/{id}/repair")
    public ResponseEntity<Map<String, String>> repairWorkflow(@PathVariable String id) {
        // Verify workflow exists
        Workflow workflow = workflowService.findById(id);

        // Check for FAILED runs
        if (!runRepository.existsByWorkflowIdAndStatus(id, RunStatus.FAILED)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workflow '" + workflow.getName() + "' has no failed runs"));
        }

        autoRepairService.repairWorkflow(id);
        return ResponseEntity.accepted().body(Map.of(
                "status", "repair_started",
                "workflowId", id,
                "workflowName", workflow.getName()));
    }

    @PostMapping("/{id}/skills")
    public ResponseEntity<WorkflowResponse> activateSkills(
            @PathVariable String id,
            @Valid @RequestBody ActivateSkillsRequest request) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.activateSkills(id, request.skillIds())));
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    public ResponseEntity<WorkflowResponse> deactivateSkill(@PathVariable String id, @PathVariable String skillId) {
        return ResponseEntity.ok(workflowMapper.toResponse(workflowService.deactivateSkill(id, skillId)));
    }

    @GetMapping("/export/{id}")
    public ExportWorkflowResponse exportWorkflow(@PathVariable String id) {
        var workflow = workflowService.findById(id);
        return new ExportWorkflowResponse(
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
    }

    // --- Workflow Versions ---

    @GetMapping("/{id}/versions")
    public PageResponse<WorkflowVersionResponse> getVersions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        workflowService.findById(id);
        var result = workflowVersionService.findByWorkflowId(id, PageRequest.of(page, size));
        return new PageResponse<>(
                workflowVersionMapper.toResponseList(result.getContent()),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()
        );
    }

    @GetMapping("/{id}/versions/{versionId}")
    public WorkflowVersionResponse getVersion(@PathVariable String id, @PathVariable String versionId) {
        WorkflowVersion version = workflowVersionService.findById(versionId);
        if (!version.getWorkflowId().equals(id)) {
            throw new com.automation.exception.ResourceNotFoundException("WorkflowVersion", versionId);
        }
        return workflowVersionMapper.toResponse(version);
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public ResponseEntity<WorkflowResponse> restoreVersion(@PathVariable String id, @PathVariable String versionId) {
        WorkflowVersion version = workflowVersionService.findById(versionId);
        if (!version.getWorkflowId().equals(id)) {
            throw new com.automation.exception.ResourceNotFoundException("WorkflowVersion", versionId);
        }
        try {
            WorkflowSnapshot snapshot = objectMapper.readValue(version.getSnapshot(), WorkflowSnapshot.class);
            UpdateWorkflowRequest request = new UpdateWorkflowRequest(
                    null, null, null,
                    snapshot.blocks(), snapshot.instructions(), snapshot.model(), snapshot.provider(),
                    snapshot.metadata(), snapshot.defaultContext(),
                    "user", "Restored from version " + version.getVersionNumber()
            );
            Workflow restored = workflowService.update(id, request);
            return ResponseEntity.ok(workflowMapper.toResponse(restored));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/import")
    public ResponseEntity<WorkflowResponse> importWorkflow(@Valid @RequestBody ImportWorkflowRequest request) {
        String projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            projectId = projectService.getOrCreateCollectionProject().getId();
        }

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
                        null,
                        null,
                        "import",
                        "Imported workflow update"
                );
                var updated = workflowService.update(existing.get().getId(), updateRequest);
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
                null
        );
        var workflow = workflowService.create(createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

}
