package com.automation.service.learning;

import com.automation.entity.*;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.automation.service.learning.MetadataExtractor.extractDouble;

@Service
@RequiredArgsConstructor
public class WorkflowStatsService {

    private final WorkflowRunRepository runRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getWorkflowStats(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        if (runs.isEmpty()) {
            return Map.of("totalRuns", 0);
        }

        RunStatistics stats = new RunStatistics(runs);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRuns", runs.size());
        result.put("completed", stats.countByStatus(RunStatus.COMPLETED));
        result.put("failed", stats.countByStatus(RunStatus.FAILED));
        result.put("cancelled", stats.countByStatus(RunStatus.CANCELLED));
        result.put("successRate", stats.getSuccessRate());
        result.put("avgDurationMs", stats.getAvgDuration().orElse(0));
        result.put("minDurationMs", stats.getMinDuration().orElse(0));
        result.put("maxDurationMs", stats.getMaxDuration().orElse(0));
        result.put("avgRating", stats.getAvgRating().orElse(0));
        result.put("ratedRuns", stats.getRatedCount());

        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStepAnalytics(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        return runs.stream()
                .flatMap(r -> r.getSteps().stream())
                .collect(Collectors.groupingBy(StepResult::getStepIndex))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> calculateStepStats(e.getKey(), e.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOptimizationSuggestions(String workflowId,
                                                                  ExecutionInsightsService insightsService,
                                                                  AgenticLearningService agenticService) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        Map<String, Object> stats = getWorkflowStats(workflowId);
        List<Map<String, Object>> stepAnalytics = getStepAnalytics(workflowId);

        int totalRuns = (int) stats.get("totalRuns");
        if (totalRuns == 0) return suggestions;

        double successRate = (double) stats.get("successRate");
        double avgDuration = (double) stats.get("avgDurationMs");

        if (successRate < 0.7 && totalRuns >= 3) {
            suggestions.add(Map.of(
                    "type", "LOW_SUCCESS_RATE",
                    "severity", "HIGH",
                    "message", String.format("Workflow has a %.0f%% success rate. Consider reviewing failing steps.", successRate * 100)
            ));
        }

        for (var step : stepAnalytics) {
            double failureRate = (double) step.get("failureRate");
            long executions = (long) step.get("totalExecutions");

            if (failureRate > 0.5 && executions >= 3) {
                suggestions.add(Map.of(
                        "type", "STEP_FAILURE_HOTSPOT",
                        "severity", "HIGH",
                        "stepIndex", step.get("stepIndex"),
                        "command", step.get("command"),
                        "message", String.format("Step %d fails %.0f%% of the time. Consider adding retry logic or fixing the command.",
                                (int) step.get("stepIndex"), failureRate * 100)
                ));
            }
        }

        for (var step : stepAnalytics) {
            double stepAvgMs = (double) step.get("avgDurationMs");
            if (stepAvgMs > 30_000 && avgDuration > 0) {
                double pctOfTotal = stepAvgMs / avgDuration * 100;
                if (pctOfTotal > 50) {
                    suggestions.add(Map.of(
                            "type", "SLOW_STEP",
                            "severity", "MEDIUM",
                            "stepIndex", step.get("stepIndex"),
                            "command", step.get("command"),
                            "message", String.format("Step %d takes %.1fs on average (%.0f%% of total). Consider optimizing.",
                                    (int) step.get("stepIndex"), stepAvgMs / 1000, pctOfTotal)
                    ));
                }
            }
        }

        long minMs = (long) stats.get("minDurationMs");
        long maxMs = (long) stats.get("maxDurationMs");
        if (minMs > 0 && maxMs > minMs * 5 && totalRuns >= 5) {
            suggestions.add(Map.of(
                    "type", "HIGH_VARIANCE",
                    "severity", "LOW",
                    "message", String.format("Execution time varies widely (%.1fs - %.1fs). Some runs may be encountering intermittent issues.",
                            minMs / 1000.0, maxMs / 1000.0)
            ));
        }

        try {
            Map<String, Object> insights = insightsService.getExecutionInsights(workflowId);
            double avgCost = extractDouble(insights, "avgCostPerRunUsd");
            if (avgCost > 0.10 && (int) insights.getOrDefault("runsWithInsights", 0) >= 3) {
                suggestions.add(Map.of(
                        "type", "HIGH_COST",
                        "severity", "MEDIUM",
                        "message", String.format("Average cost per run is $%.2f. Consider using a smaller model or caching results.", avgCost)
                ));
            }

            Map<String, Object> toolPatterns = agenticService.getToolPatterns(workflowId);
            int agenticRuns = ((Number) toolPatterns.getOrDefault("totalAgenticRuns", 0)).intValue();
            if (agenticRuns >= 3) {
                double agenticTotalCost = extractDouble(toolPatterns, "totalCostUsd");
                double avgAgenticCost = agenticTotalCost / agenticRuns;
                if (avgAgenticCost > 0.05) {
                    suggestions.add(Map.of(
                            "type", "AGENTIC_COST",
                            "severity", "MEDIUM",
                            "message", String.format("Agentic runs average $%.2f each. Consider converting successful patterns to deterministic workflows.", avgAgenticCost)
                    ));
                }
            }
        } catch (Exception e) {
            // Cost-based suggestions are optional
        }

        return suggestions;
    }

    Map<String, Object> calculateStepStats(int index, List<StepResult> steps) {
        long totalExecutions = steps.size();
        long failures = steps.stream()
                .filter(s -> s.getStatus() == StepStatus.FAILED).count();

        OptionalDouble avgMs = steps.stream()
                .filter(s -> s.getDurationMs() != null)
                .mapToLong(StepResult::getDurationMs)
                .average();

        OptionalLong maxMs = steps.stream()
                .filter(s -> s.getDurationMs() != null)
                .mapToLong(StepResult::getDurationMs)
                .max();

        String commonInput = steps.stream()
                .filter(s -> s.getInput() != null)
                .collect(Collectors.groupingBy(StepResult::getInput, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        Map<String, Object> stepStats = new LinkedHashMap<>();
        stepStats.put("stepIndex", index);
        stepStats.put("command", commonInput);
        stepStats.put("totalExecutions", totalExecutions);
        stepStats.put("failures", failures);
        stepStats.put("failureRate", totalExecutions > 0 ? (double) failures / totalExecutions : 0);
        stepStats.put("avgDurationMs", avgMs.orElse(0));
        stepStats.put("maxDurationMs", maxMs.orElse(0));

        return stepStats;
    }

    static class RunStatistics {
        private final List<WorkflowRun> runs;

        RunStatistics(List<WorkflowRun> runs) {
            this.runs = runs;
        }

        long countByStatus(RunStatus status) {
            return runs.stream().filter(r -> r.getStatus() == status).count();
        }

        double getSuccessRate() {
            if (runs.isEmpty()) return 0;
            return (double) countByStatus(RunStatus.COMPLETED) / runs.size();
        }

        OptionalDouble getAvgDuration() {
            return runs.stream()
                    .filter(r -> r.getDurationMs() != null)
                    .mapToLong(WorkflowRun::getDurationMs)
                    .average();
        }

        OptionalLong getMinDuration() {
            return runs.stream()
                    .filter(r -> r.getDurationMs() != null)
                    .mapToLong(WorkflowRun::getDurationMs)
                    .min();
        }

        OptionalLong getMaxDuration() {
            return runs.stream()
                    .filter(r -> r.getDurationMs() != null)
                    .mapToLong(WorkflowRun::getDurationMs)
                    .max();
        }

        OptionalDouble getAvgRating() {
            return runs.stream()
                    .filter(r -> r.getUserRating() != null)
                    .mapToInt(WorkflowRun::getUserRating)
                    .average();
        }

        long getRatedCount() {
            return runs.stream().filter(r -> r.getUserRating() != null).count();
        }
    }
}
