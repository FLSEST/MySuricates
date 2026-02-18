package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowMapper workflowMapper;

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

        // Cost-based suggestions from execution insights
        try {
            Map<String, Object> insights = getExecutionInsights(workflowId);
            double avgCost = extractDouble(insights, "avgCostPerRunUsd");
            if (avgCost > 0.10 && (int) insights.getOrDefault("runsWithInsights", 0) >= 3) {
                suggestions.add(Map.of(
                        "type", "HIGH_COST",
                        "severity", "MEDIUM",
                        "message", String.format("Average cost per run is $%.2f. Consider using a smaller model or caching results.", avgCost)
                ));
            }

            // Tool-pattern based: agentic vs deterministic cost comparison
            Map<String, Object> toolPatterns = getToolPatterns(workflowId);
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
            log.debug("Could not generate cost-based suggestions: {}", e.getMessage());
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
    public DashboardResponse getDashboard() {
        long totalWorkflows = workflowRepository.count();
        long totalRuns = runRepository.count();
        long successfulRuns = runRepository.countByStatus(RunStatus.COMPLETED);
        long failedRuns = runRepository.countByStatus(RunStatus.FAILED);
        double successRate = totalRuns > 0 ? (double) successfulRuns / totalRuns : 0;

        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        long runsToday = runRepository.countByStartedAtAfter(todayStart);

        // Average duration from recent runs (reuse the time-series window)
        Instant thirtyDaysAgoInstant = LocalDate.now().minusDays(30)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<WorkflowRun> recentWindow = runRepository.findByStartedAtAfterOrderByStartedAtDesc(thirtyDaysAgoInstant);
        double avgDurationMs = recentWindow.stream()
                .filter(r -> r.getDurationMs() != null)
                .mapToLong(WorkflowRun::getDurationMs)
                .average()
                .orElse(0);

        // Top 5 workflows by run count
        List<DashboardResponse.TopWorkflowEntry> topWorkflows = runRepository.findTopWorkflows().stream()
                .map(row -> new DashboardResponse.TopWorkflowEntry(
                        (String) row[0], (String) row[1], (Long) row[2]))
                .toList();

        // Recent runs (newest 10)
        List<WorkflowRunResponse> recentRuns = runRepository.findTop10ByOrderByStartedAtDesc().stream()
                .map(workflowMapper::toRunResponse)
                .toList();

        // Time series: runs per day for last 30 days
        List<DashboardResponse.TimeSeriesPoint> timeSeries = recentWindow.stream()
                .filter(r -> r.getStartedAt() != null)
                .collect(Collectors.groupingBy(r -> r.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    long count = e.getValue().size();
                    long completed = e.getValue().stream()
                            .filter(r -> r.getStatus() == RunStatus.COMPLETED).count();
                    double rate = count > 0 ? (double) completed / count : 0;
                    return new DashboardResponse.TimeSeriesPoint(e.getKey().toString(), count, rate);
                })
                .toList();

        // Aggregate cost and tokens from step metadata in recent window
        double totalCostUsd30d = 0.0;
        long totalTokens30d = 0;
        for (WorkflowRun run : recentWindow) {
            for (StepResult step : run.getSteps()) {
                if (step.getMetadata() != null) {
                    totalCostUsd30d += extractDouble(step.getMetadata(), "costUsd");
                    totalTokens30d += extractLong(step.getMetadata(), "inputTokens");
                    totalTokens30d += extractLong(step.getMetadata(), "outputTokens");
                }
            }
        }

        return new DashboardResponse(
                totalWorkflows,
                totalRuns,
                runsToday,
                successfulRuns,
                failedRuns,
                avgDurationMs,
                successRate,
                totalCostUsd30d,
                totalTokens30d,
                topWorkflows,
                recentRuns,
                timeSeries
        );
    }

    // --- Agentic learning ---

    /**
     * Retrieves agentic strategy history from past runs' step metadata.
     * Looks at steps whose metadata contains blockType="agentic" and extracts
     * goals, strategies used, and outcomes for learning.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAgenticStrategyHistory(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        return runs.stream()
                .flatMap(r -> r.getSteps().stream())
                .filter(s -> s.getMetadata() != null
                        && "agentic".equals(s.getMetadata().get("blockType")))
                .sorted(Comparator.comparing(StepResult::getStartedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(s -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    Map<String, Object> meta = s.getMetadata();
                    entry.put("goal", meta.getOrDefault("goal", "unknown"));
                    entry.put("totalIterations", meta.getOrDefault("totalIterations", 0));
                    entry.put("goalAchieved", meta.getOrDefault("goalAchieved", false));
                    entry.put("outcome", s.getStatus().name());
                    entry.put("durationMs", s.getDurationMs());

                    List<Map<String, Object>> iterations = (List<Map<String, Object>>)
                            meta.getOrDefault("iterations", List.of());
                    List<String> strategies = iterations.stream()
                            .map(i -> (String) i.getOrDefault("action", ""))
                            .filter(a -> !a.isEmpty())
                            .toList();
                    entry.put("strategy", String.join(" -> ", strategies));

                    return entry;
                })
                .toList();
    }

    /**
     * Returns agentic-specific analytics: average iterations to goal,
     * success rate, total agentic runs.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAgenticAnalytics(String workflowId) {
        List<Map<String, Object>> history = getAgenticStrategyHistory(workflowId);
        if (history.isEmpty()) {
            return Map.of("totalAgenticRuns", 0);
        }

        long total = history.size();
        long achieved = history.stream()
                .filter(h -> Boolean.TRUE.equals(h.get("goalAchieved")))
                .count();

        OptionalDouble avgIterations = history.stream()
                .mapToInt(h -> ((Number) h.getOrDefault("totalIterations", 0)).intValue())
                .average();

        Map<String, Object> analytics = new LinkedHashMap<>();
        analytics.put("totalAgenticRuns", total);
        analytics.put("achievedGoals", achieved);
        analytics.put("agenticSuccessRate", (double) achieved / total);
        analytics.put("avgIterationsToGoal", avgIterations.orElse(0));

        return analytics;
    }

    // --- Execution Insights ---

    @Transactional(readOnly = true)
    public Map<String, Object> getExecutionInsights(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);
        if (runs.isEmpty()) {
            return Map.of("totalRuns", 0);
        }

        double totalCost = 0.0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        Set<String> modelsUsed = new LinkedHashSet<>();
        int runsWithInsights = 0;

        for (WorkflowRun run : runs) {
            boolean hasInsight = false;
            for (StepResult step : run.getSteps()) {
                if (step.getMetadata() != null) {
                    double cost = extractDouble(step.getMetadata(), "costUsd");
                    long inTok = extractLong(step.getMetadata(), "inputTokens");
                    long outTok = extractLong(step.getMetadata(), "outputTokens");
                    if (cost > 0 || inTok > 0) hasInsight = true;
                    totalCost += cost;
                    totalInputTokens += inTok;
                    totalOutputTokens += outTok;
                    String model = (String) step.getMetadata().get("model");
                    if (model != null) modelsUsed.add(model);
                }
            }
            if (hasInsight) runsWithInsights++;
        }

        double avgCostPerRun = runsWithInsights > 0 ? totalCost / runsWithInsights : 0;

        Map<String, Object> insights = new LinkedHashMap<>();
        insights.put("totalRuns", runs.size());
        insights.put("runsWithInsights", runsWithInsights);
        insights.put("totalCostUsd", totalCost);
        insights.put("avgCostPerRunUsd", avgCostPerRun);
        insights.put("totalInputTokens", totalInputTokens);
        insights.put("totalOutputTokens", totalOutputTokens);
        insights.put("totalTokens", totalInputTokens + totalOutputTokens);
        insights.put("modelsUsed", modelsUsed);

        return insights;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRunInsights(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        double totalCost = 0.0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        List<Map<String, Object>> stepInsights = new ArrayList<>();

        for (StepResult step : run.getSteps()) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("stepIndex", step.getStepIndex());
            si.put("status", step.getStatus().name());
            si.put("durationMs", step.getDurationMs());

            if (step.getMetadata() != null) {
                double cost = extractDouble(step.getMetadata(), "costUsd");
                long inTok = extractLong(step.getMetadata(), "inputTokens");
                long outTok = extractLong(step.getMetadata(), "outputTokens");
                totalCost += cost;
                totalInputTokens += inTok;
                totalOutputTokens += outTok;

                si.put("costUsd", cost);
                si.put("inputTokens", inTok);
                si.put("outputTokens", outTok);
                si.put("model", step.getMetadata().get("model"));
                si.put("numTurns", step.getMetadata().get("numTurns"));
                si.put("toolNames", step.getMetadata().get("toolNames"));
                si.put("toolUseCount", step.getMetadata().get("toolUseCount"));
            }

            stepInsights.add(si);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("status", run.getStatus().name());
        result.put("totalCostUsd", totalCost);
        result.put("totalInputTokens", totalInputTokens);
        result.put("totalOutputTokens", totalOutputTokens);
        result.put("totalTokens", totalInputTokens + totalOutputTokens);
        result.put("durationMs", run.getDurationMs());
        result.put("steps", stepInsights);

        return result;
    }

    // --- Tool Pattern Learning ---

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> getToolPatterns(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);

        Map<String, Integer> toolCounts = new LinkedHashMap<>();
        List<List<String>> toolSequences = new ArrayList<>();
        int totalAgenticRuns = 0;
        double totalCost = 0.0;

        for (WorkflowRun run : runs) {
            for (StepResult step : run.getSteps()) {
                if (step.getMetadata() == null) continue;

                Object toolNamesObj = step.getMetadata().get("toolNames");
                if (toolNamesObj instanceof List<?> toolNames) {
                    List<String> names = toolNames.stream().map(Object::toString).toList();
                    for (String name : names) {
                        toolCounts.merge(name, 1, Integer::sum);
                    }
                    if (!names.isEmpty()) {
                        toolSequences.add(names);
                    }
                }

                if ("agentic".equals(step.getMetadata().get("blockType"))) {
                    totalAgenticRuns++;
                    totalCost += extractDouble(step.getMetadata(), "costUsd");

                    // Extract tool usage from iteration sub-metadata
                    Object iterations = step.getMetadata().get("iterations");
                    if (iterations instanceof List<?> iterList) {
                        for (Object iter : iterList) {
                            if (iter instanceof Map<?, ?> iterMap) {
                                extractToolsFromMeta((Map<String, Object>) iterMap, "actMeta", toolCounts);
                            }
                        }
                    }
                }
            }
        }

        // Find common tool chains (consecutive tool sequences)
        List<Map<String, Object>> patterns = findCommonPatterns(toolSequences);

        // Sort tools by count
        List<Map<String, Object>> topTools = toolCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflowId);
        result.put("totalAgenticRuns", totalAgenticRuns);
        result.put("totalCostUsd", totalCost);
        result.put("patterns", patterns);
        result.put("topTools", topTools);

        return result;
    }

    @SuppressWarnings("unchecked")
    private void extractToolsFromMeta(Map<String, Object> iterMap, String metaKey,
                                       Map<String, Integer> toolCounts) {
        Object meta = iterMap.get(metaKey);
        if (meta instanceof Map<?, ?> metaMap) {
            Object names = ((Map<String, Object>) metaMap).get("toolNames");
            if (names instanceof List<?> nameList) {
                for (Object name : nameList) {
                    toolCounts.merge(name.toString(), 1, Integer::sum);
                }
            }
        }
    }

    private List<Map<String, Object>> findCommonPatterns(List<List<String>> sequences) {
        // Find frequent 2-3 tool subsequences
        Map<String, Integer> patternCounts = new LinkedHashMap<>();

        for (List<String> seq : sequences) {
            // 2-grams
            for (int i = 0; i < seq.size() - 1; i++) {
                String pattern = seq.get(i) + " -> " + seq.get(i + 1);
                patternCounts.merge(pattern, 1, Integer::sum);
            }
            // 3-grams
            for (int i = 0; i < seq.size() - 2; i++) {
                String pattern = seq.get(i) + " -> " + seq.get(i + 1) + " -> " + seq.get(i + 2);
                patternCounts.merge(pattern, 1, Integer::sum);
            }
        }

        return patternCounts.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pattern", e.getKey());
                    m.put("occurrences", e.getValue());
                    return m;
                })
                .toList();
    }

    // --- Helper methods ---

    private double extractDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private long extractLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return 0;
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
