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

@RequiresModule(ModuleCatalog.CONVERSION)
@RestController
@RequestMapping("/api/v1/conversions")
@RequiredArgsConstructor
public class ConversionController {

    private final WorkflowConversionService conversionService;

    @GetMapping("/runs/{runId}/suggest")
    public WorkflowSuggestion suggestWorkflow(@PathVariable String runId) {
        return conversionService.suggestWorkflow(runId);
    }

    @PostMapping("/runs/{runId}/create-workflow")
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @PathVariable String runId,
            @Valid @RequestBody ConvertRunToWorkflowRequest request) {
        WorkflowResponse created = conversionService.createWorkflowFromSuggestion(
                runId, request.projectId(), request.name(), request.description(), request.blocks());
        return ResponseEntity.ok(created);
    }
}
