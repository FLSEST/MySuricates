package com.automation.service.learning;

import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;

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
    public List<Map<String, Object>> getOptimizationSuggestions(String workflowId) {
        List<Map<String, Object>> suggestions = new ArrayList<>();
        Map<String, Object> stats = getWorkflowStats(workflowId);
        List<Map<String, Object>> stepAnalytics = getStepAnalytics(workflowId);

        int totalRuns = (int) stats.get("totalRuns");
        if (totalRuns == 0) return suggestions;

        double successRate = (double) stats.get("successRate");
        double avgDuration = (double) stats.get("avgDurationMs");

        // Low success rate
        if (successRate < 0.7 && totalRuns >= 3) {
            suggestions.add(Map.of(
                    "type", "LOW_SUCCESS_RATE",
                    "severity", "HIGH",
                    "message", String.format("Workflow has a %.0f%% success rate. Consider reviewing failing steps.", successRate * 100)
            ));
        }

        // Find failure hotspot steps
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

        // Find slow steps
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

        // High duration variance
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

        return suggestions;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSimilarWorkflows(String workflowId) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        List<String> blockTypes = workflow.getBlocks().stream()
                .map(BlockDefinition::getType)
                .toList();

        if (blockTypes.isEmpty()) return List.of();

        List<Map<String, Object>> similar = new ArrayList<>();

        for (Workflow other : workflowRepository.findAll()) {
            if (other.getId().equals(workflowId)) continue;

            List<String> otherTypes = other.getBlocks().stream()
                    .map(BlockDefinition::getType)
                    .toList();

            double similarity = calculateSimilarity(blockTypes, otherTypes);
            if (similarity > 0.5) {
                similar.add(Map.of(
                        "workflowId", other.getId(),
                        "workflowName", other.getName(),
                        "similarity", Math.round(similarity * 100) + "%",
                        "blockTypes", otherTypes
                ));
            }
        }

        similar.sort((a, b) -> ((String) b.get("similarity")).compareTo((String) a.get("similarity")));
        return similar;
    }

    @Transactional
    public void rateRun(String runId, int rating) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));
        run.setUserRating(rating);
        runRepository.save(run);
        log.info("Rated run {} with {}/5", runId, rating);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard() {
        List<WorkflowRun> allRuns = runRepository.findAllWithWorkflow();

        RunStatistics stats = new RunStatistics(allRuns);
        long totalWorkflows = workflowRepository.count();

        // Most active workflows (top 5)
        Map<String, Long> runCountByWorkflow = allRuns.stream()
                .filter(r -> r.getWorkflow() != null)
                .collect(Collectors.groupingBy(r -> r.getWorkflow().getId(), Collectors.counting()));

        List<Map<String, Object>> topWorkflows = runCountByWorkflow.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    String name = allRuns.stream()
                            .filter(r -> r.getWorkflow() != null && r.getWorkflow().getId().equals(e.getKey()))
                            .findFirst()
                            .map(r -> r.getWorkflow().getName())
                            .orElse("Unknown");
                    return Map.<String, Object>of(
                            "workflowId", e.getKey(),
                            "workflowName", name,
                            "runCount", e.getValue()
                    );
                })
                .toList();

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("totalWorkflows", totalWorkflows);
        dashboard.put("totalRuns", (long) allRuns.size());
        dashboard.put("completedRuns", stats.countByStatus(RunStatus.COMPLETED));
        dashboard.put("failedRuns", stats.countByStatus(RunStatus.FAILED));
        dashboard.put("runningRuns", stats.countByStatus(RunStatus.RUNNING));
        dashboard.put("overallSuccessRate", stats.getSuccessRate());
        dashboard.put("avgDurationMs", stats.getAvgDuration().orElse(0));
        dashboard.put("topWorkflows", topWorkflows);

        return dashboard;
    }

    // --- Helper classes ---

    private Map<String, Object> calculateStepStats(int index, List<StepResult> steps) {
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

    private double calculateSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }

    private static class RunStatistics {
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
