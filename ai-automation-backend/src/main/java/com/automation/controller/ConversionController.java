package com.automation.controller;

import com.automation.dto.request.ConvertRunToWorkflowRequest;
import com.automation.dto.response.WorkflowResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.conversion.WorkflowConversionService;
import com.automation.service.conversion.WorkflowSuggestion;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Turns the successful agentic steps of a run into a reusable workflow. */
@RequiresModule(ModuleCatalog.CONVERSION)
@RestController
@RequestMapping("/api/v1/conversions")
@RequiredArgsConstructor
public class ConversionController {

    private final WorkflowConversionService conversionService;

    /**
     * Suggests a workflow built from the agentic steps of a run that reached their goal.
     * Tries an AI conversion first and falls back to rule-based; without such steps the suggestion is marked unavailable.
     */
    @GetMapping("/runs/{runId}/suggest")
    public WorkflowSuggestion suggestWorkflow(@PathVariable String runId) {
        return conversionService.suggestWorkflow(runId);
    }

    /**
     * Creates a workflow from a run's suggestion in the given project.
     * With {@code blocks} (the reviewed suggestion) exactly these are saved without a new AI call, and {@code name} is required;
     * without them the suggestion is generated again. An unknown run is 404, a run that is not COMPLETED or has nothing
     * to convert is 409; the answer is 200 with the new workflow.
     */
    @PostMapping("/runs/{runId}/create-workflow")
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @PathVariable String runId,
            @Valid @RequestBody ConvertRunToWorkflowRequest request) {
        WorkflowResponse created = conversionService.createWorkflowFromSuggestion(
                runId, request.projectId(), request.name(), request.description(), request.blocks());
        return ResponseEntity.ok(created);
    }
}
