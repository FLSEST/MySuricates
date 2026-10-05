package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorMessage;
import com.automation.exception.ErrorResponse;
import com.automation.exception.HardeningException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.learning.WorkflowSimilarityService;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.workflow.WorkflowOptimizationService;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Help with an existing workflow: optimization suggestion, similar workflows, repair after failed
 * runs. Split off from {@link WorkflowController} (6c2a2ad7), paths unchanged.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowAssistController {

    private final WorkflowService workflowService;
    private final ExecutionQueryService executionQueryService;
    private final WorkflowOptimizationService optimizationService;
    private final WorkflowSimilarityService workflowSimilarityService;
    private final AutoRepairService autoRepairService;

    /**
     * Asks the workflow's AI provider for an optimization suggestion and returns it as JSON.
     * Changes nothing; a failed AI call comes back as {@code 200} with {@code error: true} and a {@code message}.
     */
    @PostMapping("/{id}/optimize")
    public ResponseEntity<Map<String, Object>> optimizeWorkflow(@PathVariable String id) {
        Map<String, Object> suggestion = optimizationService.optimizeWorkflow(id);
        return ResponseEntity.ok(suggestion);
    }

    /**
     * Lists other workflows whose block types overlap with this one, most similar first, at most {@code limit}
     * entries. {@code limit} defaults to 5 and is capped at 20.
     */
    @GetMapping("/{id}/similar")
    public List<Map<String, Object>> getSimilarWorkflows(
            @PathVariable String id,
            @RequestParam(defaultValue = "5") int limit) {
        List<Map<String, Object>> all = workflowSimilarityService.getSimilarWorkflows(id);
        int capped = Math.max(1, Math.min(limit, 20));
        return all.size() > capped ? all.subList(0, capped) : all;
    }

    /**
     * Starts an AI repair of a workflow with failed runs in the background and answers {@code 202}.
     * Without a failed run {@code 400}; a sealed workflow is refused with {@code 409 WORKFLOW_SEALED}.
     */
    @PostMapping("/{id}/repair")
    @RequiresModule(ModuleCatalog.FACTORY)
    public ResponseEntity<?> repairWorkflow(@PathVariable String id) {
        // Verify workflow exists
        Workflow workflow = workflowService.findById(id);
        if (workflow.isSealed()) {
            // Refuse before the async repair starts: it would call the AI and only then fail on saving
            throw new HardeningException(ErrorCode.WORKFLOW_SEALED,
                    "Workflow '" + workflow.getName() + "' is sealed — unseal it before repairing it",
                    Map.of("name", String.valueOf(workflow.getName())));
        }

        // Check for FAILED runs
        if (!executionQueryService.hasFailedRuns(id)) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorMessage.NO_FAILED_RUNS,
                    Map.of("name", String.valueOf(workflow.getName()))));
        }

        autoRepairService.repairWorkflow(id);
        return ResponseEntity.accepted().body(Map.of(
                "status", "repair_started",
                "workflowId", id,
                "workflowName", workflow.getName()));
    }
}
