package com.automation.controller;

import com.automation.dto.request.CreateWorkflowTemplateRequest;
import com.automation.dto.request.UpdateWorkflowTemplateRequest;
import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.mapper.WorkflowTemplateMapper;
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

    @GetMapping
    public List<WorkflowTemplateResponse> listTemplates() {
        var templates = templateService.findAll();
        var responses = templateMapper.toResponseList(templates);
        return verificationService.enrichAllWithVerifications(responses);
    }

    @GetMapping("/{id}")
    public WorkflowTemplateResponse getTemplate(@PathVariable String id) {
        var response = templateMapper.toResponse(templateService.findById(id));
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

    @PostMapping("/from-workflow/{workflowId}")
    public ResponseEntity<WorkflowTemplateResponse> createFromWorkflow(@PathVariable String workflowId) {
        var template = templateService.createFromWorkflow(workflowId);
        return ResponseEntity.status(HttpStatus.CREATED).body(templateMapper.toResponse(template));
    }

}
