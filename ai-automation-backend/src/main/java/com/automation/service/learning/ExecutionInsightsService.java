package com.automation.service.learning;

import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.automation.service.learning.MetadataExtractor.extractDouble;
import static com.automation.service.learning.MetadataExtractor.extractLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionInsightsService {

    private final WorkflowRunRepository runRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getExecutionInsights(String workflowId) {
        List<WorkflowRun> runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);
        if (runs.isEmpty()) {
            return Map.of("totalRuns", 0);
        }

        double totalCost = 0.0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        TokenDetailTotals details = new TokenDetailTotals();
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
                    details.add(step.getMetadata(), null);
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
        // the same detail sums as the run insights (TODO 20861200) — before, only those had them
        details.putTotals(insights);
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
        TokenDetailTotals details = new TokenDetailTotals();
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
                details.add(step.getMetadata(), si);
                si.put("model", step.getMetadata().get("model"));
                si.put("provider", step.getMetadata().get("provider"));
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
        details.putTotals(result);
        result.put("durationMs", run.getDurationMs());
        result.put("steps", stepInsights);

        return result;
    }

    /**
     * Token figures only Claude reports, apart from {@code inputTokens}/{@code outputTokens}: the cached
     * input (TODO 20adda30) and the thinking part of the output (TODO 20861200). Summed over the steps
     * that carry them; a step without them adds nothing, and the totals are 0 when none did.
     */
    private static final class TokenDetailTotals {
        private static final List<String> FIELDS =
                List.of("cacheReadInputTokens", "cacheCreationInputTokens", "thinkingTokens");
        private final long[] totals = new long[FIELDS.size()];

        /** Adds a step's figures; {@code stepInsight} (nullable) gets those the step reported. */
        void add(Map<String, Object> stepMetadata, Map<String, Object> stepInsight) {
            for (int i = 0; i < FIELDS.size(); i++) {
                if (stepMetadata.get(FIELDS.get(i)) instanceof Number n) {
                    totals[i] += n.longValue();
                    if (stepInsight != null) stepInsight.put(FIELDS.get(i), n.longValue());
                }
            }
        }

        /** {@code totalCacheReadInputTokens}, {@code totalCacheCreationInputTokens}, {@code totalThinkingTokens}. */
        void putTotals(Map<String, Object> target) {
            for (int i = 0; i < FIELDS.size(); i++) {
                String field = FIELDS.get(i);
                target.put("total" + Character.toUpperCase(field.charAt(0)) + field.substring(1), totals[i]);
            }
        }
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
}
