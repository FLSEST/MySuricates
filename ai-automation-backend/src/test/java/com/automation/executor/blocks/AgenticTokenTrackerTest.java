package com.automation.executor.blocks;

import com.automation.service.ai.AiResponseMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticTokenTrackerTest {

    private AgenticTokenTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new AgenticTokenTracker();
    }

    @Test
    void captureReasonMeta_withRealUsage_storesActualData() {
        AiResponseMetadata metadata = new AiResponseMetadata(1000, 500, 0.01, "claude-sonnet-4-6", false, null, null, null, List.of());

        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureReasonMeta(iterData, metadata, "prompt", "response text", 1);

        assertThat(iterData).containsKey("reasonMeta");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) iterData.get("reasonMeta");
        assertThat(meta).doesNotContainKey("estimated");
    }

    /**
     * Cache figures are summed into the step, but stay out of the budget: {@code max_total_tokens}
     * was sized for the uncached input and would trip after one call (TODO 20adda30).
     */
    @Test
    void cacheTokens_areSummedIntoTheStep_butNotIntoTheBudget() {
        AiResponseMetadata reason = new AiResponseMetadata(3, 200, 0.01, "claude-opus-5-5", false, null, null, null,
                List.of(), 10000, 5000);
        AiResponseMetadata act = new AiResponseMetadata(5, 300, 0.02, "claude-opus-5-5", false, null, null, null,
                List.of(), 40000, 0);
        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureReasonMeta(iterData, reason, "prompt", "response", 1);
        tracker.captureActMeta(iterData, act, "action", "output", 1);
        tracker.trackIteration(iterData);

        assertThat(tracker.getRunningTotalTokens()).isEqualTo(3 + 200 + 5 + 300);
        assertThat(tracker.checkBudget(1000)).isNull();
        Map<String, Object> result = tracker.buildFinalMetadata("goal", "criteria", 1, true, null, "opus", "CLAUDE");
        assertThat(result)
                .containsEntry("inputTokens", 8)
                .containsEntry("cacheReadInputTokens", 50000L)
                .containsEntry("cacheCreationInputTokens", 5000L);
    }

    @Test
    void cacheTokens_notReported_leaveNoKeys() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureReasonMeta(iterData, AiResponseMetadata.estimated(10, 5), "prompt", "response", 1);
        tracker.captureActMeta(iterData, null, "action", "output", 1);
        tracker.trackIteration(iterData);

        assertThat(tracker.buildFinalMetadata("goal", "criteria", 1, true, null, null, "CODEX"))
                .doesNotContainKeys("cacheReadInputTokens", "cacheCreationInputTokens");
    }

    @Test
    void captureReasonMeta_withoutUsage_storesFallback() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureReasonMeta(iterData, null, "a prompt of some length", "response text", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) iterData.get("reasonMeta");
        assertThat(meta.get("estimated")).isEqualTo(true);
        assertThat(((Number) meta.get("inputTokens")).intValue()).isGreaterThan(0);
        assertThat(((Number) meta.get("outputTokens")).intValue()).isGreaterThan(0);
    }

    @Test
    void captureActMeta_withRealUsage_storesActualData() {
        AiResponseMetadata metadata = new AiResponseMetadata(2000, 800, 0.02, "claude-sonnet-4-6", false, null, null, null, List.of());

        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureActMeta(iterData, metadata, "action text", "output", 1);

        assertThat(iterData).containsKey("actMeta");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) iterData.get("actMeta");
        assertThat(meta).doesNotContainKey("estimated");
    }

    @Test
    void captureActMeta_withoutUsage_storesFallback() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        tracker.captureActMeta(iterData, null, "some action", "output", 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) iterData.get("actMeta");
        assertThat(meta.get("estimated")).isEqualTo(true);
    }

    @Test
    void trackIteration_accumulatesTokens() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50));
        iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80));

        tracker.trackIteration(iterData);

        assertThat(tracker.getRunningTotalTokens()).isEqualTo(430);
    }

    @Test
    void trackIteration_blindIterations_incrementOnEstimated() {
        Map<String, Object> iterData1 = new LinkedHashMap<>();
        iterData1.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
        iterData1.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));

        tracker.trackIteration(iterData1);
        assertThat(tracker.getConsecutiveBlindIterations()).isEqualTo(1);

        Map<String, Object> iterData2 = new LinkedHashMap<>();
        iterData2.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
        iterData2.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));

        tracker.trackIteration(iterData2);
        assertThat(tracker.getConsecutiveBlindIterations()).isEqualTo(2);
    }

    @Test
    void trackIteration_realUsageResetsBlindCounter() {
        // First: blind
        Map<String, Object> iterData1 = new LinkedHashMap<>();
        iterData1.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
        iterData1.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
        tracker.trackIteration(iterData1);
        assertThat(tracker.getConsecutiveBlindIterations()).isEqualTo(1);

        // Second: real usage on reason phase
        Map<String, Object> iterData2 = new LinkedHashMap<>();
        iterData2.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50));
        iterData2.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
        tracker.trackIteration(iterData2);
        assertThat(tracker.getConsecutiveBlindIterations()).isEqualTo(0);
    }

    @Test
    void checkBudget_underBudget_returnsNull() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50));
        iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80));
        tracker.trackIteration(iterData);

        assertThat(tracker.checkBudget(200_000)).isNull();
    }

    @Test
    void checkBudget_overBudget_returnsStopReason() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        iterData.put("reasonMeta", Map.of("inputTokens", 60000, "outputTokens", 60000));
        iterData.put("actMeta", Map.of("inputTokens", 60000, "outputTokens", 60000));
        tracker.trackIteration(iterData);

        assertThat(tracker.checkBudget(100_000)).isEqualTo("TOKEN_BUDGET_EXCEEDED");
    }

    @Test
    void checkBlindIterations_underThreshold_returnsNull() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
        iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
        tracker.trackIteration(iterData);
        tracker.trackIteration(new LinkedHashMap<>(iterData));

        assertThat(tracker.checkBlindIterations()).isNull();
    }

    @Test
    void checkBlindIterations_atThreshold_returnsStopReason() {
        for (int i = 0; i < 3; i++) {
            Map<String, Object> iterData = new LinkedHashMap<>();
            iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
            iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
            tracker.trackIteration(iterData);
        }

        assertThat(tracker.checkBlindIterations()).isEqualTo("TOKEN_TRACKING_FAILED");
    }

    @Test
    void checkBlindIterations_whenExpectEstimated_alwaysReturnsNull() {
        tracker.setExpectEstimatedTokens(true);

        for (int i = 0; i < 5; i++) {
            Map<String, Object> iterData = new LinkedHashMap<>();
            iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
            iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
            tracker.trackIteration(iterData);
        }

        assertThat(tracker.checkBlindIterations()).isNull();
    }

    @Test
    void buildFinalMetadata_aggregatesCorrectly() {
        Map<String, Object> iter1 = new LinkedHashMap<>();
        iter1.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "costUsd", 0.01));
        iter1.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "costUsd", 0.02,
                "toolNames", List.of("Read", "Write")));
        tracker.trackIteration(iter1);

        Map<String, Object> iter2 = new LinkedHashMap<>();
        iter2.put("reasonMeta", Map.of("inputTokens", 150, "outputTokens", 60, "costUsd", 0.015));
        iter2.put("actMeta", Map.of("inputTokens", 250, "outputTokens", 90, "costUsd", 0.025,
                "toolNames", List.of("Bash")));
        tracker.trackIteration(iter2);

        Map<String, Object> metadata = tracker.buildFinalMetadata(
                "Fix tests", "All pass", 2, true, null, "claude-sonnet-4-6", "CLAUDE");

        assertThat(metadata.get("blockType")).isEqualTo("agentic");
        assertThat(metadata.get("goal")).isEqualTo("Fix tests");
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(((Number) metadata.get("inputTokens")).intValue()).isEqualTo(700);
        assertThat(((Number) metadata.get("outputTokens")).intValue()).isEqualTo(280);
        assertThat(((Number) metadata.get("costUsd")).doubleValue()).isCloseTo(0.07, org.assertj.core.data.Offset.offset(0.001));
        @SuppressWarnings("unchecked")
        List<String> toolNames = (List<String>) metadata.get("toolNames");
        assertThat(toolNames).containsExactly("Read", "Write", "Bash");
        assertThat(metadata.get("toolUseCount")).isEqualTo(3);
        assertThat(metadata.get("model")).isEqualTo("claude-sonnet-4-6");
        assertThat(metadata.get("provider")).isEqualTo("CLAUDE");
        assertThat(metadata).doesNotContainKey("stopReason");
        assertThat(metadata).doesNotContainKey("tokenEstimationFallback");
    }

    @Test
    void buildFinalMetadata_includesStopReasonAndFallbackFlag() {
        Map<String, Object> iterData = new LinkedHashMap<>();
        iterData.put("reasonMeta", Map.of("inputTokens", 100, "outputTokens", 50, "estimated", true));
        iterData.put("actMeta", Map.of("inputTokens", 200, "outputTokens", 80, "estimated", true));
        tracker.trackIteration(iterData);

        Map<String, Object> metadata = tracker.buildFinalMetadata(
                "Goal", "Criteria", 1, false, "TOKEN_BUDGET_EXCEEDED", null, "CODEX");

        assertThat(metadata.get("stopReason")).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(metadata.get("tokenEstimationFallback")).isEqualTo(true);
    }
}
