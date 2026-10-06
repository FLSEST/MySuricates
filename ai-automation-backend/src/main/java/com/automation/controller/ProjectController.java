package com.automation.controller;

import com.automation.dto.request.CreateProjectRequest;
import com.automation.dto.request.OnboardingConfigRequest;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.dto.request.UpdateProjectRequest;
import com.automation.dto.response.OnboardingStatusResponse;
import com.automation.dto.response.ProjectResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.mapper.ProjectMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.example.ExampleProjectService;
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

/** Projects: create, edit, archive and restore them, start or stop their container, and manage their onboarding file. */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;
    private final ExampleProjectService exampleProjectService;
    private final WorkflowService workflowService;
    private final ProjectContextService projectContextService;
    private final ProjectOnboardingService projectOnboardingService;
    private final ProjectMapper projectMapper;
    private final WorkflowMapper workflowMapper;

    /** Lists all projects that are not archived. */
    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectMapper.toResponseList(projectService.findAll());
    }

    /**
     * Creates a project (201); {@code name}, {@code type} and {@code path} are required.
     * {@code path} must be an existing directory (under the workspace root if one is set), otherwise 400.
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        var project = projectService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(projectMapper.toResponse(project));
    }

    /**
     * The bundled example project for a fresh installation: created on the first call (201), returned
     * on every further call (200) — with any example workflow it lacks added.
     * Its files are copied to {@code automation.examples.directory}; an existing copy is never overwritten.
     * An example whose blocks need a switched-off module is left out until a later call with the module on;
     * {@code locale} ({@code de}/{@code en}, default {@code en}) picks the language of names and workflows.
     */
    @PostMapping("/example")
    public ResponseEntity<ProjectResponse> ensureExampleProject(@RequestParam(required = false) String locale) {
        var result = exampleProjectService.ensure(locale);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(projectMapper.toResponse(result.project()));
    }

    /** Returns one project; an archived project is a 404. */
    @GetMapping("/{id}")
    public ProjectResponse getProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.findById(id));
    }

    /** Updates the fields sent and keeps the others; a new {@code path} is checked like on create. */
    @PutMapping("/{id}")
    public ProjectResponse updateProject(@PathVariable String id, @RequestBody UpdateProjectRequest request) {
        return projectMapper.toResponse(projectService.update(id, request));
    }

    /**
     * Archives the project (204) and stops its container if it was running; its workflows stay.
     * {@code POST /{id}/restore} brings it back.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable String id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Lists the archived projects. */
    @GetMapping("/archived")
    public List<ProjectResponse> listArchivedProjects() {
        return projectMapper.toResponseList(projectService.findArchived());
    }

    /** Restores an archived project. */
    @PostMapping("/{id}/restore")
    public ProjectResponse restoreProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.restore(id));
    }

    /** Creates and starts the project's Docker container and returns the project with status {@code RUNNING}, or {@code ERROR} if it failed. */
    @PostMapping("/{id}/start")
    public ProjectResponse startProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.startContainer(id));
    }

    /** Stops the project's Docker container and returns the project with status {@code STOPPED}. */
    @PostMapping("/{id}/stop")
    public ProjectResponse stopProject(@PathVariable String id) {
        return projectMapper.toResponse(projectService.stopContainer(id));
    }

    /** Lists the workflows of a project with their run counts. */
    @GetMapping("/{id}/workflows")
    public List<WorkflowResponse> getProjectWorkflows(@PathVariable String id) {
        return workflowMapper.toResponseListWithRunCounts(workflowService.findByProjectId(id));
    }

    /**
     * Generates an AI context text from the project directory with the default AI provider and stores it as {@code aiContext}.
     * Returns {@code {"context": …}}; the project path must exist (else 400).
     */
    @PostMapping("/{id}/generate-context")
    public ResponseEntity<Map<String, String>> generateContext(@PathVariable String id) {
        var project = projectService.findById(id);
        String generated = projectContextService.generateContext(project.getPath());
        // Persist to DB so assistant and workflows can reuse cached context
        projectService.update(id, new UpdateProjectRequest(
                null, null, null, null, null, null, generated));
        return ResponseEntity.ok(Map.of("context", generated));
    }

    /**
     * Switches onboarding on and writes the onboarding file from the chosen profiles into the project directory.
     * {@code format} is {@code CLAUDE_RULES} (default) or {@code AGENTS_MD}; a file without the MySuricates marker is never overwritten.
     */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @PostMapping("/{id}/onboarding/enable")
    public OnboardingStatusResponse enableOnboarding(@PathVariable String id, @RequestBody OnboardingConfigRequest config) {
        var project = projectService.findById(id);
        projectOnboardingService.enable(project, config);
        return projectOnboardingService.getStatus(projectService.findById(id));
    }

    /** Switches onboarding off and deletes the onboarding file if MySuricates wrote it. */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @PostMapping("/{id}/onboarding/disable")
    public OnboardingStatusResponse disableOnboarding(@PathVariable String id) {
        var project = projectService.findById(id);
        projectOnboardingService.disable(project);
        return projectOnboardingService.getStatus(projectService.findById(id));
    }

    /** Writes the onboarding file again from the current profiles; does nothing while onboarding is off. */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @PostMapping("/{id}/onboarding/refresh")
    public OnboardingStatusResponse refreshOnboarding(@PathVariable String id) {
        var project = projectService.findById(id);
        projectOnboardingService.refresh(project);
        return projectOnboardingService.getStatus(project);
    }

    /** Returns the onboarding state: on or off, format, profiles, language, file path and whether the file exists or is foreign. */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @GetMapping("/{id}/onboarding/status")
    public OnboardingStatusResponse getOnboardingStatus(@PathVariable String id) {
        var project = projectService.findById(id);
        return projectOnboardingService.getStatus(project);
    }

    /** Returns the onboarding file text as it would be written, as {@code {"content": …}}, without writing it. */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @GetMapping("/{id}/onboarding/preview")
    public ResponseEntity<Map<String, String>> getOnboardingPreview(@PathVariable String id) {
        var project = projectService.findById(id);
        String content = projectOnboardingService.preview(project);
        return ResponseEntity.ok(Map.of("content", content));
    }
}
