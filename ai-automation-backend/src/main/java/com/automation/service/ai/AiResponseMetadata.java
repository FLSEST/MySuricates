package com.automation.service.ai;

import com.automation.service.cli.claude.ClaudeJsonResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized response metadata across AI providers.
 * For providers without real token tracking, {@code estimated} is true
 * and token counts are approximated via chars/4 heuristic.
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
        List<String> toolNames
) {
    /** Extract real metadata from a Claude CLI JSON response. */
    public static AiResponseMetadata fromClaudeResponse(ClaudeJsonResponse parsed) {
        if (parsed == null) {
            return estimated(0, 0);
        }
        int input = parsed.usage() != null ? parsed.usage().inputTokens() : 0;
        int output = parsed.usage() != null ? parsed.usage().outputTokens() : 0;
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
                tools
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
