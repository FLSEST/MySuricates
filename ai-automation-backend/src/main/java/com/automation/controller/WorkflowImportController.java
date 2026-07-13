package com.automation.controller;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.Workflow;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.export.ImportedWorkflow;
import com.automation.service.export.ProcessFormatImporter;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Import externer Prozessformate als NEUER Workflow (Roundtrip zum Export in
 * {@link WorkflowController}). Eigener Controller, damit {@code WorkflowController} unter der
 * Ratchet-Decke bleibt. Spec: docs/plans/2026-07-13-bpmn-import-design.md.
 */
@RestController
@RequestMapping("/api/v1/workflows/import")
@RequiredArgsConstructor
public class WorkflowImportController {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final ProcessFormatImporter bpmnImportAdapter;

    /**
     * Importiert einen zuvor exportierten BPMN-Prozess (mit {@code mysuricates:blockConfig}) als
     * neuen Workflow im Projekt. Fremd-BPMN, kaputtes XML oder zu große Eingaben → 400; es wird
     * nichts angelegt, wenn der Import scheitert (Parsen + Validieren vor {@code create}).
     */
    @PostMapping(value = "/bpmn", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<WorkflowResponse> importWorkflowFromBpmn(
            @RequestParam String projectId, @RequestBody String xml) {
        ImportedWorkflow imported = bpmnImportAdapter.importFrom(xml);
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                projectId, imported.name(), null, null, imported.blocks(),
                null, null, null, null, null, null, null, null, null);
        Workflow workflow = workflowService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }
}
