package com.automation.controller;

import com.automation.dto.request.ActivateSkillsRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.ExecuteWorkflowRequest;
import com.automation.dto.request.ImportWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.dto.response.ExportWorkflowResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowOptimizationService;
import com.automation.service.workflow.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private static final String COLLECTION_PROJECT_NAME = "Sammlung";

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowMapper workflowMapper;
    private final WorkflowOptimizationService optimizationService;

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
    public WorkflowResponse getWorkflow(@PathVariable String id) {
        return workflowMapper.toResponse(workflowService.findById(id));
    }

    @PutMapping("/{id}")
    public WorkflowResponse updateWorkflow(@PathVariable String id, @RequestBody UpdateWorkflowRequest request) {
        return workflowMapper.toResponse(workflowService.update(id, request));
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

        Workflow workflow = workflowService.findById(id);
        Map<String, Object> context = request != null ? request.context() : Map.of();

        WorkflowRun run = workflowExecutor.execute(workflow, context);

        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    @GetMapping("/{id}/runs")
    public List<WorkflowRunResponse> getWorkflowRuns(@PathVariable String id) {
        var runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(id);
        return workflowMapper.toRunResponseList(runs);
    }

    @GetMapping("/{id}/runs/{runId}")
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

    @PostMapping("/{id}/skills")
    public WorkflowResponse activateSkills(
            @PathVariable String id,
            @Valid @RequestBody ActivateSkillsRequest request) {
        return workflowMapper.toResponse(workflowService.activateSkills(id, request.skillIds()));
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    public WorkflowResponse deactivateSkill(@PathVariable String id, @PathVariable String skillId) {
        return workflowMapper.toResponse(workflowService.deactivateSkill(id, skillId));
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
                Instant.now()
        );
    }

    @PostMapping("/import")
    public ResponseEntity<WorkflowResponse> importWorkflow(@Valid @RequestBody ImportWorkflowRequest request) {
        String projectId = request.projectId();
        if (projectId == null || projectId.isBlank()) {
            projectId = getOrCreateCollectionProject().getId();
        }
        var createRequest = new CreateWorkflowRequest(
                projectId,
                request.name(),
                request.description(),
                request.icon(),
                request.blocks(),
                List.of(),
                request.instructions(),
                request.model()
        );
        var workflow = workflowService.create(createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }

    private Project getOrCreateCollectionProject() {
        return projectRepository.findFirstByName(COLLECTION_PROJECT_NAME)
                .orElseGet(() -> {
                    Project project = Project.builder()
                            .name(COLLECTION_PROJECT_NAME)
                            .subtitle("Importierte Workflows")
                            .type(ProjectType.PERSONAL)
                            .path("/tmp")
                            .status(ProjectStatus.STOPPED)
                            .build();
                    return projectRepository.save(project);
                });
    }
}
