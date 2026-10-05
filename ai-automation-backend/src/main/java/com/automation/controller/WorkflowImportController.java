package com.automation.controller;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
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
 * Imports external process formats as a NEW workflow (round trip to the export in
 * {@link WorkflowController}). A controller of its own so that {@code WorkflowController} stays
 * below the size ratchet.
 */
@RequiresModule(ModuleCatalog.BPMN)
@RestController
@RequestMapping("/api/v1/workflows/import")
@RequiredArgsConstructor
public class WorkflowImportController {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final ProcessFormatImporter bpmnImportAdapter;

    /**
     * Imports a previously exported BPMN process (with {@code mysuricates:blockConfig}) as a new
     * workflow in the project. Foreign BPMN, broken XML or oversized input → 400; nothing is created
     * when the import fails (parsing and validation run before {@code create}).
     */
    @PostMapping(value = "/bpmn", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<WorkflowResponse> importWorkflowFromBpmn(
            @RequestParam String projectId, @RequestBody String xml) {
        ImportedWorkflow imported = bpmnImportAdapter.importFrom(xml);
        CreateWorkflowRequest request = new CreateWorkflowRequest(
                projectId, imported.name(), null, null, imported.blocks(),
                null, null, null, null, null, null, null, null, null);
        Workflow workflow = workflowService.create(request, ChangeSource.BPMN_IMPORT);
        return ResponseEntity.status(HttpStatus.CREATED).body(workflowMapper.toResponse(workflow));
    }
}
