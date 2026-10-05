package com.automation.controller;

import com.automation.dto.request.CreateWorkflowTemplateRequest;
import com.automation.dto.request.InstallTemplateRequest;
import com.automation.dto.request.UpdateWorkflowTemplateRequest;
import com.automation.dto.response.TemplateInstallResponse;
import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.mapper.TemplateResponseLocalizer;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.service.workflow.TemplatePackageInstaller;
import com.automation.service.workflow.TemplateVerificationService;
import com.automation.service.workflow.WorkflowTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Workflow templates and blueprints: list, maintain, create from a workflow, and install into a project. */
@RestController
@RequestMapping("/api/v1/workflow-templates")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService templateService;
    private final WorkflowTemplateMapper templateMapper;
    private final TemplateVerificationService verificationService;
    private final TemplatePackageInstaller packageInstaller;

    /**
     * Lists all templates and blueprints by {@code sortOrder}, each with the projects it was verified in.
     *
     * @param locale {@code de}/{@code en}: language of the steps of bilingual templates; without it English
     */
    @GetMapping
    public List<WorkflowTemplateResponse> listTemplates(@RequestParam(required = false) String locale) {
        var templates = templateService.findAll();
        var responses = TemplateResponseLocalizer.localize(templates, templateMapper.toResponseList(templates), locale);
        return verificationService.enrichAllWithVerifications(responses);
    }

    /** Returns one template; {@code locale} as for the list. */
    @GetMapping("/{id}")
    public WorkflowTemplateResponse getTemplate(@PathVariable String id, @RequestParam(required = false) String locale) {
        var template = templateService.findById(id);
        var response = TemplateResponseLocalizer.localize(template, templateMapper.toResponse(template), locale);
        return verificationService.enrichWithVerifications(response);
    }

    /** Creates a user template ({@code builtIn} is always {@code false}) and answers {@code 201}. */
    @PostMapping
    public ResponseEntity<WorkflowTemplateResponse> createTemplate(
            @Valid @RequestBody CreateWorkflowTemplateRequest request) {
        var template = templateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(templateMapper.toResponse(template));
    }

    /** Changes the given fields of a template; fields left out stay as they are. */
    @PutMapping("/{id}")
    public WorkflowTemplateResponse updateTemplate(
            @PathVariable String id,
            @RequestBody UpdateWorkflowTemplateRequest request) {
        return templateMapper.toResponse(templateService.update(id, request));
    }

    /** Deletes a user template ({@code 204}); a built-in template is refused with {@code 409}. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a workflow from the template in a project, plus the blueprints its sub-workflow blocks
     * call, wired to their new ids (TODO 093d2f11). An unknown referenced blueprint is a 404 and nothing
     * is created. A blank {@code name} or {@code description} takes the template's in {@code locale}
     * (default English); {@code metadata} goes to the new workflow only, not to the blueprints it calls.
     */
    @PostMapping("/{id}/install")
    public ResponseEntity<TemplateInstallResponse> installTemplate(
            @PathVariable String id, @Valid @RequestBody InstallTemplateRequest request) {
        var installed = packageInstaller.install(id, request.projectId(), request.name(), request.description(),
                request.locale(), request.metadata());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TemplateInstallResponse(
                installed.workflow().getId(), installed.workflow().getName(),
                installed.parts().stream().map(w -> w.getId()).toList()));
    }

    /** Saves a workflow's name, description, blocks, instructions and model as a new user template ({@code 201}). */
    @PostMapping("/from-workflow/{workflowId}")
    public ResponseEntity<WorkflowTemplateResponse> createFromWorkflow(@PathVariable String workflowId) {
        var template = templateService.createFromWorkflow(workflowId);
        return ResponseEntity.status(HttpStatus.CREATED).body(templateMapper.toResponse(template));
    }

}
