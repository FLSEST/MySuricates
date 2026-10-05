package com.automation.controller;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowVersionResponse;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.mapper.WorkflowVersionMapper;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versionsgeschichte eines Workflows: auflisten, einzeln abrufen, wiederherstellen.
 * Aus {@link WorkflowController} herausgelöst (6c2a2ad7), Pfade unverändert.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowVersionController {

    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;

    /** Lists the workflow's versions, newest first, paginated ({@code page}, {@code size}). */
    @GetMapping("/{id}/versions")
    public PageResponse<WorkflowVersionResponse> getVersions(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        workflowService.findById(id);
        var result = workflowVersionService.findByWorkflowId(id, PageRequest.of(page, size));
        return new PageResponse<>(
                workflowVersionMapper.toResponseList(result.getContent()),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()
        );
    }

    /** Returns one version with its definition snapshot; a version of another workflow answers {@code 404}. */
    @GetMapping("/{id}/versions/{versionId}")
    public WorkflowVersionResponse getVersion(@PathVariable String id, @PathVariable String versionId) {
        WorkflowVersion version = workflowVersionService.findById(versionId);
        if (!version.getWorkflowId().equals(id)) {
            throw new ResourceNotFoundException("WorkflowVersion", versionId);
        }
        return workflowVersionMapper.toResponse(version);
    }

    /**
     * Sets the workflow's definition back to this version; the change is recorded as a new version.
     * A sealed workflow is refused with {@code 409 WORKFLOW_SEALED}.
     */
    @PostMapping("/{id}/versions/{versionId}/restore")
    public ResponseEntity<WorkflowResponse> restoreVersion(@PathVariable String id, @PathVariable String versionId) {
        WorkflowVersion version = workflowVersionService.findById(versionId);
        if (!version.getWorkflowId().equals(id)) {
            throw new ResourceNotFoundException("WorkflowVersion", versionId);
        }
        WorkflowSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(version.getSnapshot(), WorkflowSnapshot.class);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        // outside the catch: a sealed workflow must answer 409 WORKFLOW_SEALED, not a bare 500
        Workflow restored = workflowService.restoreFromSnapshot(id, snapshot, "Restored from version " + version.getVersionNumber());
        return ResponseEntity.ok(workflowMapper.toResponse(restored));
    }
}
