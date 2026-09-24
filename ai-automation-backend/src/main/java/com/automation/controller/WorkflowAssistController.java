package com.automation.controller;

import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
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
 * Hilfen rund um einen bestehenden Workflow: Optimierungsvorschlag, ähnliche Workflows,
 * Reparatur nach fehlgeschlagenen Runs. Aus {@link WorkflowController} herausgelöst
 * (6c2a2ad7), Pfade unverändert.
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

    @PostMapping("/{id}/optimize")
    public ResponseEntity<Map<String, Object>> optimizeWorkflow(@PathVariable String id) {
        Map<String, Object> suggestion = optimizationService.optimizeWorkflow(id);
        return ResponseEntity.ok(suggestion);
    }

    @GetMapping("/{id}/similar")
    public List<Map<String, Object>> getSimilarWorkflows(
            @PathVariable String id,
            @RequestParam(defaultValue = "5") int limit) {
        List<Map<String, Object>> all = workflowSimilarityService.getSimilarWorkflows(id);
        int capped = Math.max(1, Math.min(limit, 20));
        return all.size() > capped ? all.subList(0, capped) : all;
    }

    @PostMapping("/{id}/repair")
    @RequiresModule(ModuleCatalog.FACTORY)
    public ResponseEntity<Map<String, String>> repairWorkflow(@PathVariable String id) {
        // Verify workflow exists
        Workflow workflow = workflowService.findById(id);
        if (workflow.isSealed()) {
            // Refuse before the async repair starts: it would call the AI and only then fail on saving
            throw new HardeningException(ErrorCode.WORKFLOW_SEALED,
                    "Workflow '" + workflow.getName() + "' is sealed — unseal it before repairing it");
        }

        // Check for FAILED runs
        if (!executionQueryService.hasFailedRuns(id)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Workflow '" + workflow.getName() + "' has no failed runs"));
        }

        autoRepairService.repairWorkflow(id);
        return ResponseEntity.accepted().body(Map.of(
                "status", "repair_started",
                "workflowId", id,
                "workflowName", workflow.getName()));
    }
}
