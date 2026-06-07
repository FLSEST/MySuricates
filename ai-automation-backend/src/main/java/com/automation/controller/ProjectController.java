package com.automation.controller;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.OnboardingConfigRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.dto.response.OnboardingStatusResponse;
import com.automation.dto.response.ProjectResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.project.ProjectContextService;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
import com.automation.service.workflow.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final WorkflowService workflowService;
    private final ProjectContextService projectContextService;
    private final ProjectOnboardingService projectOnboardingService;
    private final ProjectMapper projectMapper;
    private final WorkflowMapper workflowMapper;

    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectMapper.toResponseList(projectService.findAll());
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        var project = projectService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(projectMapper.toResponse(project));
    }

    @GetMapping("/{id}")
    public ProjectResponse getProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.findById(id));
    }

    @PutMapping("/{id}")
    public ProjectResponse updateProject(@PathVariable String id, @RequestBody UpdateProjectRequest request) {
        return projectMapper.toResponse(projectService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/archived")
    public List<ProjectResponse> listArchivedProjects() {
        return projectMapper.toResponseList(projectService.findArchived());
    }

    @PostMapping("/{id}/restore")
    public ProjectResponse restoreProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.restore(id));
    }

    @PostMapping("/{id}/start")
    public ProjectResponse startProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.startContainer(id));
    }

    @PostMapping("/{id}/stop")
    public ProjectResponse stopProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.stopContainer(id));
    }

    @GetMapping("/{id}/workflows")
    public List<WorkflowResponse> getProjectWorkflows(@PathVariable String id) {
        return workflowMapper.toResponseListWithRunCounts(workflowService.findByProjectId(id));
    }

    @PostMapping("/{id}/generate-context")
    public ResponseEntity<Map<String, String>> generateContext(@PathVariable String id) {
        var project = projectService.findById(id);
        String generated = projectContextService.generateContext(project.getPath());
        // Persist to DB so assistant and workflows can reuse cached context
        projectService.update(id, new UpdateProjectRequest(
                null, null, null, null, null, null, generated));
        return ResponseEntity.ok(Map.of("context", generated));
    }

    @PostMapping("/{id}/onboarding/enable")
    public OnboardingStatusResponse enableOnboarding(@PathVariable String id, @RequestBody OnboardingConfigRequest config) {
        var project = projectService.findById(id);
        projectOnboardingService.enable(project, config);
        return projectOnboardingService.getStatus(projectService.findById(id));
    }

    @PostMapping("/{id}/onboarding/disable")
    public OnboardingStatusResponse disableOnboarding(@PathVariable String id) {
        var project = projectService.findById(id);
        projectOnboardingService.disable(project);
        return projectOnboardingService.getStatus(projectService.findById(id));
    }

    @PostMapping("/{id}/onboarding/refresh")
    public OnboardingStatusResponse refreshOnboarding(@PathVariable String id) {
        var project = projectService.findById(id);
        projectOnboardingService.refresh(project);
        return projectOnboardingService.getStatus(project);
    }

    @GetMapping("/{id}/onboarding/status")
    public OnboardingStatusResponse getOnboardingStatus(@PathVariable String id) {
        var project = projectService.findById(id);
        return projectOnboardingService.getStatus(project);
    }

    @GetMapping("/{id}/onboarding/preview")
    public ResponseEntity<Map<String, String>> getOnboardingPreview(@PathVariable String id) {
        var project = projectService.findById(id);
        String content = projectOnboardingService.preview(project);
        return ResponseEntity.ok(Map.of("content", content));
    }
}
