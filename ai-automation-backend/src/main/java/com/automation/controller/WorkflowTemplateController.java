package com.automation.controller;

import com.automation.dto.request.CreateWorkflowTemplateRequest;
import com.automation.dto.request.UpdateWorkflowTemplateRequest;
import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.dto.response.WorkflowTemplateResponse.TemplateVerificationInfo;
import com.automation.entity.TemplateVerification;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.repository.ProjectRepository;
import com.automation.service.workflow.TemplateVerificationService;
import com.automation.service.workflow.WorkflowTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/workflow-templates")
@RequiredArgsConstructor
public class WorkflowTemplateController {

    private final WorkflowTemplateService templateService;
    private final WorkflowTemplateMapper templateMapper;
    private final TemplateVerificationService verificationService;
    private final ProjectRepository projectRepository;

    @GetMapping
    public List<WorkflowTemplateResponse> listTemplates() {
        var templates = templateService.findAll();
        var responses = templateMapper.toResponseList(templates);

        // Build project name cache for all verifications
        Map<String, String> projectNames = projectRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));

        return responses.stream()
                .map(r -> enrichWithVerifications(r, projectNames))
                .toList();
    }

    @GetMapping("/{id}")
    public WorkflowTemplateResponse getTemplate(@PathVariable String id) {
        var response = templateMapper.toResponse(templateService.findById(id));
        Map<String, String> projectNames = projectRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getId(), p -> p.getName()));
        return enrichWithVerifications(response, projectNames);
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

    private WorkflowTemplateResponse enrichWithVerifications(WorkflowTemplateResponse response, Map<String, String> projectNames) {
        List<TemplateVerification> verifications = verificationService.getVerifications(response.id());
        List<TemplateVerificationInfo> infos = verifications.stream()
                .map(v -> new TemplateVerificationInfo(
                        v.getProjectId(),
                        projectNames.getOrDefault(v.getProjectId(), "Unknown"),
                        v.getLastRunId(),
                        v.getVerifiedAt()
                ))
                .toList();

        return new WorkflowTemplateResponse(
                response.id(),
                response.name(),
                response.description(),
                response.icon(),
                response.category(),
                response.blocks(),
                response.instructions(),
                response.model(),
                response.builtIn(),
                response.sortOrder(),
                response.qualityStatus(),
                infos,
                response.version(),
                response.createdAt(),
                response.updatedAt()
        );
    }
}
