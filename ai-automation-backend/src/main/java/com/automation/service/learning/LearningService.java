package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.*;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static com.automation.service.learning.MetadataExtractor.extractDouble;
import static com.automation.service.learning.MetadataExtractor.extractLong;

@Service
@RequiredArgsConstructor
public class LearningService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowMapper workflowMapper;
    private final WorkflowStatsService statsService;
    private final WorkflowSimilarityService similarityService;
    private final AgenticLearningService agenticService;
    private final ExecutionInsightsService insightsService;

    @Transactional(readOnly = true)
    public Map<String, Object> getWorkflowStats(String workflowId) {
        return statsService.getWorkflowStats(workflowId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStepAnalytics(String workflowId) {
        return statsService.getStepAnalytics(workflowId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getOptimizationSuggestions(String workflowId) {
        return statsService.getOptimizationSuggestions(workflowId, insightsService, agenticService);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSimilarWorkflows(String workflowId) {
        return similarityService.getSimilarWorkflows(workflowId);
    }

    @Transactional
    public void rateRun(String runId, int rating) {
        insightsService.rateRun(runId, rating);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAgenticStrategyHistory(String workflowId) {
        return agenticService.getAgenticStrategyHistory(workflowId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgenticAnalytics(String workflowId) {
        return agenticService.getAgenticAnalytics(workflowId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getToolPatterns(String workflowId) {
        return agenticService.getToolPatterns(workflowId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getExecutionInsights(String workflowId) {
        return insightsService.getExecutionInsights(workflowId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRunInsights(String runId) {
        return insightsService.getRunInsights(runId);
    }

    /** Strips trailing date version suffixes like {@code -20251001} from model IDs. */
    static String normalizeModelId(String model) {
        return model.replaceAll("-\\d{8,}$", "");
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

        Instant thirtyDaysAgoInstant = LocalDate.now().minusDays(30)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<WorkflowRun> recentWindow = runRepository.findByStartedAtAfterOrderByStartedAtDesc(thirtyDaysAgoInstant);
        double avgDurationMs = recentWindow.stream()
                .filter(r -> r.getDurationMs() != null)
                .mapToLong(WorkflowRun::getDurationMs)
                .average()
                .orElse(0);

        List<DashboardResponse.TopWorkflowEntry> topWorkflows = runRepository.findTopWorkflows().stream()
                .map(row -> new DashboardResponse.TopWorkflowEntry(
                        (String) row[0], (String) row[1], (Long) row[2]))
                .toList();

        List<WorkflowRunResponse> recentRuns = runRepository.findTop10ByOrderByStartedAtDesc().stream()
                .map(workflowMapper::toRunResponse)
                .toList();

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

        double totalCostUsd30d = 0.0;
        long totalTokens30d = 0;

        Map<String, long[]> providerRunCount = new LinkedHashMap<>();
        Map<String, long[]> providerSuccessCount = new LinkedHashMap<>();
        Map<String, long[]> providerTokens = new LinkedHashMap<>();
        Map<String, double[]> providerCost = new LinkedHashMap<>();
        Map<String, long[]> modelStepCount = new LinkedHashMap<>();
        Map<String, long[]> modelTokens = new LinkedHashMap<>();
        Map<String, double[]> modelCost = new LinkedHashMap<>();

        for (WorkflowRun run : recentWindow) {
            String runProvider = null;
            boolean runSuccess = run.getStatus() == RunStatus.COMPLETED;
            long runTokens = 0;
            double runCost = 0.0;

            for (StepResult step : run.getSteps()) {
                if (step.getMetadata() == null) continue;
                Map<String, Object> meta = step.getMetadata();

                double stepCost = extractDouble(meta, "costUsd");
                long stepTokens = extractLong(meta, "inputTokens") + extractLong(meta, "outputTokens");
                totalCostUsd30d += stepCost;
                totalTokens30d += stepTokens;
                runCost += stepCost;
                runTokens += stepTokens;

                if (runProvider == null && meta.get("provider") instanceof String p) runProvider = p;

                if (meta.get("model") instanceof String m && !m.isBlank()) {
                    String nm = normalizeModelId(m);
                    modelStepCount.computeIfAbsent(nm, k -> new long[1])[0]++;
                    modelTokens.computeIfAbsent(nm, k -> new long[1])[0] += stepTokens;
                    modelCost.computeIfAbsent(nm, k -> new double[1])[0] += stepCost;
                }
            }

            if (runProvider != null) {
                providerRunCount.computeIfAbsent(runProvider, k -> new long[1])[0]++;
                if (runSuccess) providerSuccessCount.computeIfAbsent(runProvider, k -> new long[1])[0]++;
                providerTokens.computeIfAbsent(runProvider, k -> new long[1])[0] += runTokens;
                providerCost.computeIfAbsent(runProvider, k -> new double[1])[0] += runCost;
            }
        }

        List<DashboardResponse.ProviderStats> providerStats = providerRunCount.entrySet().stream()
                .map(e -> {
                    String p = e.getKey();
                    long runs = e.getValue()[0];
                    long successes = providerSuccessCount.getOrDefault(p, new long[]{0})[0];
                    long tokens = providerTokens.getOrDefault(p, new long[]{0})[0];
                    double cost = providerCost.getOrDefault(p, new double[]{0.0})[0];
                    return new DashboardResponse.ProviderStats(p, runs, tokens, cost, runs > 0 ? (double) successes / runs : 0.0);
                })
                .sorted(Comparator.comparingLong(DashboardResponse.ProviderStats::runCount).reversed())
                .toList();

        List<DashboardResponse.ModelStats> modelStats = modelStepCount.entrySet().stream()
                .map(e -> {
                    String m = e.getKey();
                    long steps = e.getValue()[0];
                    long tokens = modelTokens.getOrDefault(m, new long[]{0})[0];
                    double cost = modelCost.getOrDefault(m, new double[]{0.0})[0];
                    return new DashboardResponse.ModelStats(m, steps, tokens, cost);
                })
                .sorted(Comparator.comparingLong(DashboardResponse.ModelStats::totalTokens).reversed())
                .toList();

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
                timeSeries,
                providerStats,
                modelStats
        );
    }
}
