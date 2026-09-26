package com.automation.executor.blocks;

import com.automation.service.ai.AiResponseMetadata;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Tracks token usage, cost, and tool names across agentic block iterations.
 * Handles fallback estimation when real usage data is unavailable.
 */
@Slf4j
public class AgenticTokenTracker {

    private int runningTotalTokens;
    private int consecutiveBlindIterations;
    private boolean usingFallbackEstimation;
    private final List<Map<String, Object>> iterationLog = new ArrayList<>();

    /**
     * When true, blind-iteration abort is disabled (e.g. for Codex where tokens are always estimated).
     */
    @Setter
    private boolean expectEstimatedTokens;

    /**
     * Captures token/cost metadata from the REASON phase into the iteration data map.
     * Accepts normalized {@link AiResponseMetadata} from any provider.
     */
    public void captureReasonMeta(Map<String, Object> iterData, AiResponseMetadata metadata,
                                   String promptText, String responseText, int iteration) {
        if (metadata != null && !metadata.estimated()) {
            iterData.put("reasonMeta", metadata.toMap());
        } else {
            int estimatedInput = promptText.length() / 4;
            int estimatedOutput = responseText.length() / 4;
            iterData.put("reasonMeta", Map.of(
                    "inputTokens", estimatedInput,
                    "outputTokens", estimatedOutput,
                    "estimated", true));
            if (!expectEstimatedTokens) {
                log.warn("Agentic iteration {}: REASON phase returned no usage data — using fallback estimation ({} in, {} out)",
                        iteration, estimatedInput, estimatedOutput);
            }
        }
    }

    /**
     * Captures token/cost metadata from the ACT phase into the iteration data map.
     * Accepts normalized {@link AiResponseMetadata} from any provider.
     */
    public void captureActMeta(Map<String, Object> iterData, AiResponseMetadata metadata,
                                String actionText, String outputText, int iteration) {
        if (metadata != null && !metadata.estimated()) {
            iterData.put("actMeta", metadata.toMap());
        } else {
            int estimatedInput = actionText.length() / 4;
            int estimatedOutput = outputText.length() / 4;
            iterData.put("actMeta", Map.of(
                    "inputTokens", estimatedInput,
                    "outputTokens", estimatedOutput,
                    "estimated", true));
            if (!expectEstimatedTokens) {
                log.warn("Agentic iteration {}: ACT phase returned no usage data — using fallback estimation ({} in, {} out)",
                        iteration, estimatedInput, estimatedOutput);
            }
        }
    }

    /**
     * Updates running token totals and blind-iteration tracking from an iteration's data.
     */
    public void trackIteration(Map<String, Object> iterData) {
        runningTotalTokens += extractTokenCount(iterData, "reasonMeta", "inputTokens");
        runningTotalTokens += extractTokenCount(iterData, "reasonMeta", "outputTokens");
        runningTotalTokens += extractTokenCount(iterData, "actMeta", "inputTokens");
        runningTotalTokens += extractTokenCount(iterData, "actMeta", "outputTokens");

        boolean reasonEstimated = isEstimated(iterData, "reasonMeta");
        boolean actEstimated = isEstimated(iterData, "actMeta");
        if (reasonEstimated && actEstimated) {
            consecutiveBlindIterations++;
            usingFallbackEstimation = true;
        } else {
            consecutiveBlindIterations = 0;
        }

        iterationLog.add(iterData);
    }

    /**
     * Checks if the token budget has been exceeded.
     * @return stop reason string if budget exceeded, null otherwise
     */
    public String checkBudget(int maxTotalTokens) {
        if (maxTotalTokens > 0 && runningTotalTokens > maxTotalTokens) {
            return "TOKEN_BUDGET_EXCEEDED";
        }
        return null;
    }

    /**
     * Checks if too many consecutive iterations lacked real usage data.
     * Disabled when {@code expectEstimatedTokens} is true (e.g. Codex provider).
     * @return stop reason string if tracking failed, null otherwise
     */
    public String checkBlindIterations() {
        if (!expectEstimatedTokens && consecutiveBlindIterations >= 3) {
            return "TOKEN_TRACKING_FAILED";
        }
        return null;
    }

    /**
     * Builds the final aggregated metadata map for the step result.
     */
    public Map<String, Object> buildFinalMetadata(String goal, String successCriteria,
                                                    int totalIterations, boolean goalAchieved,
                                                    String stopReason, String model, String provider) {
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        double totalCostUsd = 0.0;
        List<String> allToolNames = new ArrayList<>();

        for (Map<String, Object> iter : iterationLog) {
            totalInputTokens += extractTokenCount(iter, "reasonMeta", "inputTokens");
            totalInputTokens += extractTokenCount(iter, "actMeta", "inputTokens");
            totalOutputTokens += extractTokenCount(iter, "reasonMeta", "outputTokens");
            totalOutputTokens += extractTokenCount(iter, "actMeta", "outputTokens");
            totalCostUsd += extractCost(iter, "reasonMeta");
            totalCostUsd += extractCost(iter, "actMeta");
            allToolNames.addAll(extractToolNames(iter, "actMeta"));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goal", goal);
        metadata.put("successCriteria", successCriteria);
        metadata.put("totalIterations", totalIterations);
        metadata.put("goalAchieved", goalAchieved);
        if (model != null) metadata.put("model", model);
        if (provider != null) metadata.put("provider", provider);
        metadata.put("inputTokens", totalInputTokens);
        metadata.put("outputTokens", totalOutputTokens);
        putCacheTotal(metadata, "cacheReadInputTokens");
        putCacheTotal(metadata, "cacheCreationInputTokens");
        metadata.put("costUsd", totalCostUsd);
        if (!allToolNames.isEmpty()) {
            metadata.put("toolNames", allToolNames);
            metadata.put("toolUseCount", allToolNames.size());
        }
        metadata.put("totalTokensUsed", runningTotalTokens);
        if (usingFallbackEstimation) {
            metadata.put("tokenEstimationFallback", true);
        }
        if (stopReason != null) {
            metadata.put("stopReason", stopReason);
        }
        metadata.put("iterations", iterationLog);

        return metadata;
    }

    public int getRunningTotalTokens() {
        return runningTotalTokens;
    }

    public int getConsecutiveBlindIterations() {
        return consecutiveBlindIterations;
    }

    // --- Private helpers ---

    /**
     * Sums a cache field over all phases that reported it (TODO 20adda30). Stays out of the budget
     * on purpose: {@code max_total_tokens} was sized for input without cache and would trip at once.
     * No phase reported it (Codex, Pi, estimates) — no key at all instead of a misleading 0.
     */
    private void putCacheTotal(Map<String, Object> metadata, String field) {
        long total = 0;
        boolean reported = false;
        for (Map<String, Object> iter : iterationLog) {
            for (String metaKey : List.of("reasonMeta", "actMeta")) {
                if (iter.get(metaKey) instanceof Map<?, ?> map && map.get(field) instanceof Number n) {
                    total += n.longValue();
                    reported = true;
                }
            }
        }
        if (reported) {
            metadata.put(field, total);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isEstimated(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(((Map<String, Object>) map).get("estimated"));
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static int extractTokenCount(Map<String, Object> iterData, String metaKey, String field) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get(field);
            if (val instanceof Number n) return n.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private double extractCost(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get("costUsd");
            if (val instanceof Number n) return n.doubleValue();
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractToolNames(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get("toolNames");
            if (val instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }
        return List.of();
    }
}
