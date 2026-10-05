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

/**
 * Run statistics: dashboard figures, platform counts, per-workflow and per-run analytics, and run ratings.
 * Dashboard, platform stats and run rating are core; analytics belong to the learning module.
 */
@RestController
@RequestMapping("/api/v1/learning")
@RequiredArgsConstructor
public class LearningController {

    private final LearningService learningService;
    private final PlatformStatsService platformStatsService;

    /**
     * Returns the dashboard figures: all-time run counts and success rate, plus cost, tokens, time series and model stats of the last {@code days} days.
     * {@code projectId} restricts every value, the overall counts included, to that project's workflows and runs.
     * {@code recentRuns} holds at most the ten newest runs of the window, without steps.
     */
    @GetMapping("/dashboard")
    public DashboardResponse getDashboard(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String projectId) {
        return learningService.getDashboard(days, projectId);
    }

    /** Returns platform-wide counts: projects, workflows, skills, templates, schedules, webhooks, chains, triggers, TODOs and wishes. */
    @GetMapping("/platform-stats")
    public PlatformStatsResponse getPlatformStats() {
        return platformStatsService.getPlatformStats();
    }

    /** Returns run statistics of one workflow: counts per outcome, success rate, durations and average rating. */
    @GetMapping("/workflows/{workflowId}/stats")
    @RequiresModule(ModuleCatalog.LEARNING)
    public Map<String, Object> getWorkflowStats(@PathVariable String workflowId) {
        return learningService.getWorkflowStats(workflowId);
    }

    /** Returns statistics per step index of a workflow across all its runs. */
    @GetMapping("/workflows/{workflowId}/step-analytics")
    @RequiresModule(ModuleCatalog.LEARNING)
    public List<Map<String, Object>> getStepAnalytics(@PathVariable String workflowId) {
        return learningService.getStepAnalytics(workflowId);
    }

    /** Returns optimization hints derived from the workflow's runs, e.g. low success rate, failing or slow steps, high cost. */
    @GetMapping("/workflows/{workflowId}/suggestions")
    @RequiresModule(ModuleCatalog.LEARNING)
    public List<Map<String, Object>> getOptimizationSuggestions(@PathVariable String workflowId) {
        return learningService.getOptimizationSuggestions(workflowId);
    }

    /** Lists other workflows with a similar set of block types, most similar first. */
    @GetMapping("/workflows/{workflowId}/similar")
    @RequiresModule(ModuleCatalog.LEARNING)
    public List<Map<String, Object>> getSimilarWorkflows(@PathVariable String workflowId) {
        return learningService.getSimilarWorkflows(workflowId);
    }

    /** Lists the most recent agentic steps of a workflow with goal, iterations, outcome and the sequence of actions taken. */
    @GetMapping("/workflows/{workflowId}/agentic-history")
    @RequiresModule(ModuleCatalog.LEARNING)
    public List<Map<String, Object>> getAgenticStrategyHistory(@PathVariable String workflowId) {
        return learningService.getAgenticStrategyHistory(workflowId);
    }

    /** Summarizes the recent agentic steps of a workflow: how often the goal was reached and the average iterations. */
    @GetMapping("/workflows/{workflowId}/agentic-analytics")
    @RequiresModule(ModuleCatalog.LEARNING)
    public Map<String, Object> getAgenticAnalytics(@PathVariable String workflowId) {
        return learningService.getAgenticAnalytics(workflowId);
    }

    /** Returns which tools the workflow's AI steps used, how often, and recurring tool sequences. */
    @GetMapping("/workflows/{workflowId}/tool-patterns")
    @RequiresModule(ModuleCatalog.LEARNING)
    public Map<String, Object> getToolPatterns(@PathVariable String workflowId) {
        return learningService.getToolPatterns(workflowId);
    }

    /** Returns cost and token sums over all runs of a workflow, plus the models used. */
    @GetMapping("/workflows/{workflowId}/execution-insights")
    @RequiresModule(ModuleCatalog.LEARNING)
    public Map<String, Object> getExecutionInsights(@PathVariable String workflowId) {
        return learningService.getExecutionInsights(workflowId);
    }

    /** Returns cost and token sums of one run plus the figures per step. */
    @GetMapping("/runs/{runId}/insights")
    @RequiresModule(ModuleCatalog.LEARNING)
    public Map<String, Object> getRunInsights(@PathVariable String runId) {
        return learningService.getRunInsights(runId);
    }

    /** Stores a user rating for a run; the query parameter {@code rating} must be between 1 and 5. */
    @PostMapping("/runs/{runId}/rate")
    public ResponseEntity<Void> rateRun(
            @PathVariable String runId,
            @RequestParam @Min(1) @Max(5) int rating) {
        learningService.rateRun(runId, rating);
        return ResponseEntity.ok().build();
    }
}
