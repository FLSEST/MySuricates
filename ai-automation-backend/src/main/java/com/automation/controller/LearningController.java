package com.automation.controller;

import com.automation.dto.response.DashboardResponse;
import com.automation.dto.response.PlatformStatsResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.learning.LearningService;
import com.automation.service.learning.PlatformStatsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiresModule(ModuleCatalog.LEARNING)
@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;
    private final PlatformStatsService platformStatsService;

    @GetMapping("/dashboard")
    public DashboardResponse getDashboard(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String projectId) {
        return learningService.getDashboard(days, projectId);
    }

    @GetMapping("/platform-stats")
    public PlatformStatsResponse getPlatformStats() {
        return platformStatsService.getPlatformStats();
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

    @GetMapping("/workflows/{workflowId}/agentic-history")
    public List<Map<String, Object>> getAgenticStrategyHistory(@PathVariable String workflowId) {
        return learningService.getAgenticStrategyHistory(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/agentic-analytics")
    public Map<String, Object> getAgenticAnalytics(@PathVariable String workflowId) {
        return learningService.getAgenticAnalytics(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/tool-patterns")
    public Map<String, Object> getToolPatterns(@PathVariable String workflowId) {
        return learningService.getToolPatterns(workflowId);
    }

    @GetMapping("/workflows/{workflowId}/execution-insights")
    public Map<String, Object> getExecutionInsights(@PathVariable String workflowId) {
        return learningService.getExecutionInsights(workflowId);
    }

    @GetMapping("/runs/{runId}/insights")
    public Map<String, Object> getRunInsights(@PathVariable String runId) {
        return learningService.getRunInsights(runId);
    }

    @PostMapping("/runs/{runId}/rate")
    public ResponseEntity<Void> rateRun(
            @PathVariable String runId,
            @RequestParam @Min(1) @Max(5) int rating) {
        learningService.rateRun(runId, rating);
        return ResponseEntity.ok().build();
    }
}
