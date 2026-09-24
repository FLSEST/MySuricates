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
import static com.automation.service.learning.MetadataExtractor.extractString;

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

    /** Safely resolve workflow from run, returning null if the workflow was soft-deleted. */
    private Workflow safeGetWorkflow(WorkflowRun run) {
        try {
            Workflow w = run.getWorkflow();
            if (w != null) w.getName(); // force proxy init (getId() doesn't trigger it)
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    public DashboardResponse getDashboard() {
        return getDashboard(30, null);
    }

    public DashboardResponse getDashboard(int days, String projectId) {
        Instant todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        DashboardOverview overview = DashboardOverview.load(runRepository, workflowRepository, projectId, todayStart);

        Instant windowStart = LocalDate.now().minusDays(days)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<WorkflowRun> recentWindow = runRepository.findDashboardWindow(windowStart);

        // Apply optional project filter
        if (projectId != null && !projectId.isBlank()) {
            recentWindow = recentWindow.stream()
                    .filter(r -> {
                        Workflow w = safeGetWorkflow(r);
                        return w != null && w.getProject() != null
                                && projectId.equals(w.getProject().getId());
                    })
                    .toList();
        }

        double avgDurationMs = recentWindow.stream()
                .filter(r -> r.getDurationMs() != null)
                .mapToLong(WorkflowRun::getDurationMs)
                .average()
                .orElse(0);

        // recentRuns: the "recent runs" widget needs the full steps (incl. input/output) for at
        // most 10 runs. findDashboardWindow no longer fetches steps (that would drag every step's
        // large TEXT columns across the whole window), so fetch them for just these 10 runs.
        // Eager workflow+project+steps because getDashboard runs without a transaction (OSIV off).
        List<String> recentIds = recentWindow.stream()
                .limit(10)
                .map(WorkflowRun::getId)
                .toList();
        List<WorkflowRunResponse> recentRuns;
        if (recentIds.isEmpty()) {
            recentRuns = List.of();
        } else {
            Map<String, WorkflowRun> recentById = runRepository.findByIdInWithStepsAndWorkflow(recentIds).stream()
                    .collect(Collectors.toMap(WorkflowRun::getId, r -> r, (a, b) -> a));
            recentRuns = recentIds.stream()
                    .map(recentById::get)
                    .filter(Objects::nonNull)
                    .map(workflowMapper::toRunResponse)
                    .toList();
        }

        // Per-step aggregation source: a lightweight projection (status + metadata only), NOT the
        // full StepResult entities — avoids loading the large input/output/context_delta TEXT
        // columns for every step in the window (getDashboard never reads them). Keyed by run id;
        // ordered by stepIndex so the "first non-integration step wins" provider logic stays stable.
        Map<String, List<StepAgg>> stepsByRun = new HashMap<>();
        for (Object[] row : runRepository.findStepMetadataForDashboard(windowStart)) {
            String stepRunId = (String) row[0];
            StepStatus stepStatus = (StepStatus) row[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> stepMeta = (Map<String, Object>) row[2];
            stepsByRun.computeIfAbsent(stepRunId, k -> new ArrayList<>()).add(new StepAgg(stepStatus, stepMeta));
        }

        // --- Per-run aggregation pass ---
        double totalCostUsd = 0.0;
        long totalTokens = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;

        // Per-run cost/tokens for time series
        Map<String, double[]> runCostMap = new HashMap<>();
        Map<String, long[]> runTokensMap = new HashMap<>();

        // Provider aggregation
        Map<String, long[]> providerRunCount = new LinkedHashMap<>();
        Map<String, long[]> providerSuccessCount = new LinkedHashMap<>();
        Map<String, long[]> providerTokens = new LinkedHashMap<>();
        Map<String, double[]> providerCost = new LinkedHashMap<>();

        // Integration aggregation (per step, not per run — a single run can use multiple integrations)
        Map<String, long[]> integrationStepCount = new LinkedHashMap<>();
        Map<String, long[]> integrationSuccessCount = new LinkedHashMap<>();
        Map<String, long[]> integrationTokens = new LinkedHashMap<>();

        // Model aggregation
        Map<String, long[]> modelStepCount = new LinkedHashMap<>();
        Map<String, long[]> modelTokens = new LinkedHashMap<>();
        Map<String, double[]> modelCost = new LinkedHashMap<>();

        // Gate status
        Map<String, long[]> gateStatusCounts = new LinkedHashMap<>();

        // Failure reasons
        Map<String, long[]> failureReasonCounts = new LinkedHashMap<>();

        // Cost by workflow
        Map<String, double[]> workflowCostMap = new LinkedHashMap<>();
        Map<String, long[]> workflowRunCountMap = new LinkedHashMap<>();
        Map<String, String> workflowNameMap = new LinkedHashMap<>();

        // Block type usage
        Map<String, long[]> blockTypeStepCount = new LinkedHashMap<>();
        Map<String, long[]> blockTypeFailedCount = new LinkedHashMap<>();

        Map<TriggerCategory, long[]> triggerCounts = new EnumMap<>(TriggerCategory.class);

        for (WorkflowRun run : recentWindow) {
            String runProvider = null;
            boolean runSuccess = run.getStatus() == RunStatus.COMPLETED;
            double runCost = 0.0;
            long runTokens = 0;

            triggerCounts.computeIfAbsent(TriggerCategory.of(run), k -> new long[1])[0]++;

            // Gate status from run metadata
            if (run.getMetadata() != null) {
                Object gateStatus = run.getMetadata().get("gate_status");
                if (gateStatus instanceof String gs && !gs.isBlank()) {
                    gateStatusCounts.computeIfAbsent(gs, k -> new long[1])[0]++;
                }
            }

            // Failure reasons
            if (run.getStatus() == RunStatus.FAILED && run.getError() != null) {
                Object msg = run.getError().get("message");
                if (msg instanceof String reason && !reason.isBlank()) {
                    String truncated = reason.length() > 100 ? reason.substring(0, 100) + "..." : reason;
                    failureReasonCounts.computeIfAbsent(truncated, k -> new long[1])[0]++;
                }
            }

            // Cost by workflow
            Workflow wf = safeGetWorkflow(run);
            String wfId = wf != null ? wf.getId() : "unknown";
            String wfName = wf != null ? wf.getName() : "Unknown";
            workflowRunCountMap.computeIfAbsent(wfId, k -> new long[1])[0]++;
            workflowNameMap.putIfAbsent(wfId, wfName);

            for (StepAgg step : stepsByRun.getOrDefault(run.getId(), List.of())) {
                if (step.metadata() == null) continue;
                Map<String, Object> meta = step.metadata();

                double stepCost = extractDouble(meta, "costUsd");
                long stepInput = extractLong(meta, "inputTokens");
                long stepOutput = extractLong(meta, "outputTokens");
                long stepTokens = stepInput + stepOutput;
                totalCostUsd += stepCost;
                totalTokens += stepTokens;
                totalInputTokens += stepInput;
                totalOutputTokens += stepOutput;
                runCost += stepCost;
                runTokens += stepTokens;

                String execMode = extractString(meta, "executionMode");
                boolean isIntegrationStep = "integration".equals(execMode);

                // Only AI providers count for providerStats — integration block metadata
                // also stores a `provider` (trello/jira/google-calendar/...) but those are
                // connectors, not AI providers, so we skip them here.
                if (runProvider == null && meta.get("provider") instanceof String p && !isIntegrationStep) {
                    runProvider = p;
                }

                // Integration stats: per-step aggregation by integration ID (trello, jira,
                // google-calendar, gmail, ...). Tokens/cost are typically zero for these.
                if (isIntegrationStep && meta.get("provider") instanceof String integrationId
                        && !integrationId.isBlank()) {
                    integrationStepCount.computeIfAbsent(integrationId, k -> new long[1])[0]++;
                    if (step.status() == StepStatus.COMPLETED) {
                        integrationSuccessCount.computeIfAbsent(integrationId, k -> new long[1])[0]++;
                    }
                    integrationTokens.computeIfAbsent(integrationId, k -> new long[1])[0] += stepTokens;
                }

                if (meta.get("model") instanceof String m && !m.isBlank()) {
                    String nm = normalizeModelId(m);
                    modelStepCount.computeIfAbsent(nm, k -> new long[1])[0]++;
                    modelTokens.computeIfAbsent(nm, k -> new long[1])[0] += stepTokens;
                    modelCost.computeIfAbsent(nm, k -> new double[1])[0] += stepCost;
                }

                // Block type usage
                String blockType = extractString(meta, "blockType");
                if (blockType != null && !blockType.isBlank()) {
                    blockTypeStepCount.computeIfAbsent(blockType, k -> new long[1])[0]++;
                    if (step.status() == StepStatus.FAILED) {
                        blockTypeFailedCount.computeIfAbsent(blockType, k -> new long[1])[0]++;
                    }
                }
            }

            // Store per-run aggregates for time series
            runCostMap.put(run.getId(), new double[]{runCost});
            runTokensMap.put(run.getId(), new long[]{runTokens});

            // Accumulate cost by workflow
            workflowCostMap.computeIfAbsent(wfId, k -> new double[1])[0] += runCost;

            if (runProvider != null) {
                providerRunCount.computeIfAbsent(runProvider, k -> new long[1])[0]++;
                if (runSuccess) providerSuccessCount.computeIfAbsent(runProvider, k -> new long[1])[0]++;
                providerTokens.computeIfAbsent(runProvider, k -> new long[1])[0] += runTokens;
                providerCost.computeIfAbsent(runProvider, k -> new double[1])[0] += runCost;
            }
        }

        // --- Time series with extended fields ---
        List<DashboardResponse.TimeSeriesPoint> timeSeries = recentWindow.stream()
                .filter(r -> r.getStartedAt() != null)
                .collect(Collectors.groupingBy(r -> r.getStartedAt().atZone(ZoneId.systemDefault()).toLocalDate()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    List<WorkflowRun> dayRuns = e.getValue();
                    long count = dayRuns.size();
                    long completed = dayRuns.stream()
                            .filter(r -> r.getStatus() == RunStatus.COMPLETED
                                    || r.getStatus() == RunStatus.COMPLETED_WITH_ERRORS).count();
                    long failed = dayRuns.stream()
                            .filter(r -> r.getStatus() == RunStatus.FAILED).count();
                    double rate = count > 0 ? (double) completed / count : 0;
                    double dayAvgDuration = dayRuns.stream()
                            .filter(r -> r.getDurationMs() != null)
                            .mapToLong(WorkflowRun::getDurationMs)
                            .average().orElse(0);
                    double dayCost = dayRuns.stream()
                            .mapToDouble(r -> runCostMap.getOrDefault(r.getId(), new double[]{0})[0])
                            .sum();
                    long dayTokens = dayRuns.stream()
                            .mapToLong(r -> runTokensMap.getOrDefault(r.getId(), new long[]{0})[0])
                            .sum();
                    return new DashboardResponse.TimeSeriesPoint(
                            e.getKey().toString(), count, rate, failed, dayAvgDuration, dayCost, dayTokens);
                })
                .toList();

        // --- Build response lists ---
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

        List<DashboardResponse.IntegrationStats> integrationStats = integrationStepCount.entrySet().stream()
                .map(e -> {
                    String p = e.getKey();
                    long runs = e.getValue()[0];
                    long successes = integrationSuccessCount.getOrDefault(p, new long[]{0})[0];
                    long tokens = integrationTokens.getOrDefault(p, new long[]{0})[0];
                    return new DashboardResponse.IntegrationStats(p, runs, tokens, runs > 0 ? (double) successes / runs : 0.0);
                })
                .sorted(Comparator.comparingLong(DashboardResponse.IntegrationStats::runCount).reversed())
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

        List<DashboardResponse.GateStatusEntry> gateStatusSummary = gateStatusCounts.entrySet().stream()
                .map(e -> new DashboardResponse.GateStatusEntry(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.comparingLong(DashboardResponse.GateStatusEntry::count).reversed())
                .toList();

        List<DashboardResponse.FailureReasonEntry> failureReasons = failureReasonCounts.entrySet().stream()
                .map(e -> new DashboardResponse.FailureReasonEntry(e.getKey(), e.getValue()[0]))
                .sorted(Comparator.comparingLong(DashboardResponse.FailureReasonEntry::count).reversed())
                .limit(10)
                .toList();

        List<DashboardResponse.CostByWorkflowEntry> costByWorkflow = workflowCostMap.entrySet().stream()
                .map(e -> new DashboardResponse.CostByWorkflowEntry(
                        e.getKey(),
                        workflowNameMap.getOrDefault(e.getKey(), "Unknown"),
                        e.getValue()[0],
                        workflowRunCountMap.getOrDefault(e.getKey(), new long[]{0})[0]))
                .sorted(Comparator.comparingDouble(DashboardResponse.CostByWorkflowEntry::totalCostUsd).reversed())
                .limit(10)
                .toList();

        List<DashboardResponse.BlockTypeUsageEntry> blockTypeUsage = blockTypeStepCount.entrySet().stream()
                .map(e -> {
                    String bt = e.getKey();
                    long steps = e.getValue()[0];
                    long failed = blockTypeFailedCount.getOrDefault(bt, new long[]{0})[0];
                    double failRate = steps > 0 ? (double) failed / steps : 0;
                    return new DashboardResponse.BlockTypeUsageEntry(bt, steps, failed, failRate);
                })
                .sorted(Comparator.comparingLong(DashboardResponse.BlockTypeUsageEntry::stepCount).reversed())
                .toList();

        var triggerDistribution = TriggerCategory.distribution(triggerCounts);

        return new DashboardResponse(
                overview.totalWorkflows(),
                overview.totalRuns(),
                overview.runsToday(),
                overview.successfulRuns(),
                overview.failedRuns(),
                avgDurationMs,
                overview.successRate(),
                totalCostUsd,
                totalTokens,
                totalInputTokens,
                totalOutputTokens,
                overview.topWorkflows(),
                recentRuns,
                timeSeries,
                providerStats,
                integrationStats,
                modelStats,
                gateStatusSummary,
                failureReasons,
                costByWorkflow,
                blockTypeUsage,
                triggerDistribution
        );
    }

    /** Lightweight per-step aggregation row sourced from {@code findStepMetadataForDashboard}
     *  (status + metadata only) instead of the full StepResult entity. */
    private record StepAgg(StepStatus status, Map<String, Object> metadata) {}
}
