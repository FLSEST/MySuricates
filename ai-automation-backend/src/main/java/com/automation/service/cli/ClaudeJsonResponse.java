package com.automation.service.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeJsonResponse(
        String result,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("total_cost_usd") Double totalCostUsd,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("duration_api_ms") Long durationApiMs,
        @JsonProperty("num_turns") Integer numTurns,
        Usage usage,
        @JsonProperty("modelUsage") Map<String, ModelUsage> modelUsage,
        List<ToolUseEvent> toolUses
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelUsage(int inputTokens, int outputTokens, double costUSD) {}

    public record ToolUseEvent(String name, Map<String, Object> input) {}

    public String model() {
        return modelUsage != null ? modelUsage.keySet().stream().findFirst().orElse(null) : null;
    }

    public Map<String, Object> toMetadata() {
        var meta = new LinkedHashMap<String, Object>();
        if (usage != null) {
            meta.put("inputTokens", usage.inputTokens());
            meta.put("outputTokens", usage.outputTokens());
        }
        if (totalCostUsd != null) meta.put("costUsd", totalCostUsd);
        if (durationApiMs != null) meta.put("apiDurationMs", durationApiMs);
        if (numTurns != null) meta.put("numTurns", numTurns);
        if (model() != null) meta.put("model", model());
        if (sessionId != null) meta.put("sessionId", sessionId);
        if (toolUses != null && !toolUses.isEmpty()) {
            meta.put("toolNames", toolUses.stream().map(ToolUseEvent::name).toList());
            meta.put("toolUseCount", toolUses.size());
        }
        return meta;
    }
}
