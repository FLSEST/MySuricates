package com.automation.service.ai;

import com.automation.service.cli.claude.ClaudeJsonResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized response metadata across AI providers.
 * For providers without real token tracking, {@code estimated} is true
 * and token counts are approximated via chars/4 heuristic.
 *
 * <p>{@code inputTokens} is what the provider reports as input: for Claude the input the prompt
 * cache did not serve. The cached part comes separately as {@code cacheReadInputTokens} and
 * {@code cacheCreationInputTokens} (TODO 20adda30) — {@code null} when the provider does not report
 * it (Codex, Pi, older Claude CLIs). The agentic token budget keeps counting {@code inputTokens}
 * only, so its limits mean what they meant before.
 *
 * <p>{@code thinkingTokens} is the part of {@code outputTokens} the model spent on extended thinking
 * (TODO 20861200) — included in {@code outputTokens}, never added on top; {@code null} when not reported.
 */
public record AiResponseMetadata(
        int inputTokens,
        int outputTokens,
        Double costUsd,
        String model,
        boolean estimated,
        Long durationApiMs,
        Integer numTurns,
        String sessionId,
        List<String> toolNames,
        Integer cacheReadInputTokens,
        Integer cacheCreationInputTokens,
        Integer thinkingTokens
) {
    /** Metadata without thinking figures. */
    public AiResponseMetadata(int inputTokens, int outputTokens, Double costUsd, String model, boolean estimated,
                              Long durationApiMs, Integer numTurns, String sessionId, List<String> toolNames,
                              Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {
        this(inputTokens, outputTokens, costUsd, model, estimated, durationApiMs, numTurns, sessionId, toolNames,
                cacheReadInputTokens, cacheCreationInputTokens, null);
    }

    /** Metadata without cache figures. */
    public AiResponseMetadata(int inputTokens, int outputTokens, Double costUsd, String model, boolean estimated,
                              Long durationApiMs, Integer numTurns, String sessionId, List<String> toolNames) {
        this(inputTokens, outputTokens, costUsd, model, estimated, durationApiMs, numTurns, sessionId, toolNames,
                null, null, null);
    }

    /** Extract real metadata from a Claude CLI JSON response. */
    public static AiResponseMetadata fromClaudeResponse(ClaudeJsonResponse parsed) {
        if (parsed == null) {
            return estimated(0, 0);
        }
        ClaudeJsonResponse.Usage usage = parsed.usage();
        int input = usage != null ? usage.inputTokens() : 0;
        int output = usage != null ? usage.outputTokens() : 0;
        List<String> tools = parsed.toolUses() != null
                ? parsed.toolUses().stream().map(ClaudeJsonResponse.ToolUseEvent::name).toList()
                : List.of();

        return new AiResponseMetadata(
                input, output,
                parsed.totalCostUsd(),
                parsed.model(),
                input == 0 && output == 0,
                parsed.durationApiMs(),
                parsed.numTurns(),
                parsed.sessionId(),
                tools,
                usage != null ? usage.cacheReadInputTokens() : null,
                usage != null ? usage.cacheCreationInputTokens() : null,
                usage != null ? usage.thinkingTokens() : null
        );
    }

    /** Estimate tokens from text lengths (chars / 4 heuristic). */
    public static AiResponseMetadata estimated(String inputText, String outputText) {
        int inputTokens = inputText != null ? inputText.length() / 4 : 0;
        int outputTokens = outputText != null ? outputText.length() / 4 : 0;
        return estimated(inputTokens, outputTokens);
    }

    /** Create estimated metadata with pre-calculated token counts. */
    public static AiResponseMetadata estimated(int inputTokens, int outputTokens) {
        return new AiResponseMetadata(inputTokens, outputTokens, null, null, true, null, null, null, List.of());
    }

    /** Convert to the Map format used by step/run metadata. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("inputTokens", inputTokens);
        map.put("outputTokens", outputTokens);
        if (cacheReadInputTokens != null) map.put("cacheReadInputTokens", cacheReadInputTokens);
        if (cacheCreationInputTokens != null) map.put("cacheCreationInputTokens", cacheCreationInputTokens);
        if (thinkingTokens != null) map.put("thinkingTokens", thinkingTokens);
        if (costUsd != null) map.put("costUsd", costUsd);
        if (model != null) map.put("model", model);
        if (estimated) map.put("estimated", true);
        if (durationApiMs != null) map.put("apiDurationMs", durationApiMs);
        if (numTurns != null) map.put("numTurns", numTurns);
        if (sessionId != null) map.put("sessionId", sessionId);
        if (toolNames != null && !toolNames.isEmpty()) {
            map.put("toolNames", toolNames);
            map.put("toolUseCount", toolNames.size());
        }
        return map;
    }
}
