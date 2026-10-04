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

@RestController
@RequestMapping("/api/v1/workflow-templates")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService templateService;
    private final WorkflowTemplateMapper templateMapper;
    private final TemplateVerificationService verificationService;
    private final TemplatePackageInstaller packageInstaller;

    /** @param locale {@code de}/{@code en}: language of the steps of bilingual templates; without it English */
    @GetMapping
    public List<WorkflowTemplateResponse> listTemplates(@RequestParam(required = false) String locale) {
        var templates = templateService.findAll();
        var responses = TemplateResponseLocalizer.localize(templates, templateMapper.toResponseList(templates), locale);
        return verificationService.enrichAllWithVerifications(responses);
    }

    @GetMapping("/{id}")
    public WorkflowTemplateResponse getTemplate(@PathVariable String id, @RequestParam(required = false) String locale) {
        var template = templateService.findById(id);
        var response = TemplateResponseLocalizer.localize(template, templateMapper.toResponse(template), locale);
        return verificationService.enrichWithVerifications(response);
    }

    @PostMapping
    public ResponseEntity<WorkflowTemplateResponse> createTemplate(
            @Valid @RequestBody CreateWorkflowTemplateRequest request) {
        var template = templateService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(templateMapper.toResponse(template));
    }

    @PutMapping("/{id}")
    public WorkflowTemplateResponse updateTemplate(
            @PathVariable String id,
            @RequestBody UpdateWorkflowTemplateRequest request) {
        return templateMapper.toResponse(templateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a workflow from the template in a project, plus the blueprints its sub-workflow blocks
     * call, wired to their new ids (TODO 093d2f11).
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

    @PostMapping("/from-workflow/{workflowId}")
    public ResponseEntity<WorkflowTemplateResponse> createFromWorkflow(@PathVariable String workflowId) {
        var template = templateService.createFromWorkflow(workflowId);
        return ResponseEntity.status(HttpStatus.CREATED).body(templateMapper.toResponse(template));
    }

}
