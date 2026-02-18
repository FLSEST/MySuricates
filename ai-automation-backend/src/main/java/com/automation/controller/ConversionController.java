package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.service.conversion.WorkflowConversionService;
import com.automation.service.conversion.WorkflowSuggestion;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
            @RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        String name = body.get("name");

        if (projectId == null || projectId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        WorkflowResponse created = conversionService.createWorkflowFromSuggestion(runId, projectId, name);
        return ResponseEntity.ok(created);
    }
}
