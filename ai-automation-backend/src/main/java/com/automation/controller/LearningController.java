package com.automation.controller;

import com.automation.dto.response.DashboardResponse;
import com.automation.service.learning.LearningService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard() {
        return learningService.getDashboard();
    }

    @GetMapping("/workflows/{workflowId}/stats")
    public Map<String, Object> getWorkflowStats(@PathVariable String workflowId) {
        return learningService.getWorkflowStats(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/step-analytics")
    public List<Map<String, Object>> getStepAnalytics(@PathVariable String workflowId) {
        return learningService.getStepAnalytics(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/suggestions")
    public List<Map<String, Object>> getOptimizationSuggestions(@PathVariable String workflowId) {
        return learningService.getOptimizationSuggestions(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/similar")
    public List<Map<String, Object>> getSimilarWorkflows(@PathVariable String workflowId) {
        return learningService.getSimilarWorkflows(workflowId);
    }

    @PostMapping("/runs/{runId}/rate")
    public ResponseEntity<Void> rateRun(
            @PathVariable String runId,
            @RequestParam @Min(1) @Max(5) int rating) {
        learningService.rateRun(runId, rating);
        return ResponseEntity.ok().build();
    }
}
