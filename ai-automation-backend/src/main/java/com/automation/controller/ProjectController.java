package com.automation.controller;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.dto.response.ProjectResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.project.ProjectContextService;
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
        return workflowMapper.toResponseList(workflowService.findByProjectId(id));
    }

    @PostMapping("/{id}/generate-context")
    public ResponseEntity<Map<String, String>> generateContext(@PathVariable String id) {
        var project = projectService.findById(id);
        String generated = projectContextService.generateContext(project.getPath());
        return ResponseEntity.ok(Map.of("context", generated));
    }
}
