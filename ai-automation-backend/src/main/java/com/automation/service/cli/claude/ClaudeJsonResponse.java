package com.automation.service.cli.claude;

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
        String assistantModel,
        List<ToolUseEvent> toolUses,
        String subtype,
        @JsonProperty("is_error") Boolean isError
) {
    public ClaudeJsonResponse(
            String result,
            String sessionId,
            Double totalCostUsd,
            Long durationMs,
            Long durationApiMs,
            Integer numTurns,
            Usage usage,
            Map<String, ModelUsage> modelUsage,
            String assistantModel,
            List<ToolUseEvent> toolUses
    ) {
        this(result, sessionId, totalCostUsd, durationMs, durationApiMs, numTurns, usage, modelUsage,
                assistantModel, toolUses, null, null);
    }

    public ClaudeJsonResponse(
            String result,
            String sessionId,
            Double totalCostUsd,
            Long durationMs,
            Long durationApiMs,
            Integer numTurns,
            Usage usage,
            Map<String, ModelUsage> modelUsage,
            List<ToolUseEvent> toolUses
    ) {
        this(result, sessionId, totalCostUsd, durationMs, durationApiMs, numTurns, usage, modelUsage, null, toolUses);
    }

    /**
     * {@code usage} of the result line, summed over all turns of the call. {@code input_tokens} counts
     * only the input the prompt cache did not serve — with Claude Code's cached system prompt that is
     * a handful of tokens (TODO 20adda30); the bulk sits in the two cache fields. They are nullable in
     * the CLI's schema and missing in older versions: {@code null} then, never a made-up 0.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("output_tokens_details") OutputTokensDetails outputTokensDetails
    ) {
        public Usage(int inputTokens, int outputTokens) {
            this(inputTokens, outputTokens, null, null, null);
        }

        public Usage(int inputTokens, int outputTokens, Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {
            this(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, null);
        }

        /**
         * The thinking part of {@code output_tokens} ({@code output_tokens_details.thinking_tokens}, TODO 20861200) —
         * already counted in {@link #outputTokens()}, never on top. {@code null} when the CLI did not report it.
         */
        public Integer thinkingTokens() {
            return outputTokensDetails != null ? outputTokensDetails.thinkingTokens() : null;
        }

        /** Puts the cache and thinking fields that the CLI reported into step/run metadata. */
        public void putCacheTokens(Map<String, Object> meta) {
            if (cacheReadInputTokens != null) meta.put("cacheReadInputTokens", cacheReadInputTokens);
            if (cacheCreationInputTokens != null) meta.put("cacheCreationInputTokens", cacheCreationInputTokens);
            if (thinkingTokens() != null) meta.put("thinkingTokens", thinkingTokens());
        }
    }

    /** {@code usage.output_tokens_details} of the CLI: how much of the output was extended thinking. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputTokensDetails(@JsonProperty("thinking_tokens") Integer thinkingTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelUsage(int inputTokens, int outputTokens, double costUSD) {}

    public record ToolUseEvent(String name, Map<String, Object> input) {}

    /**
     * Whether the CLI's result line reports a failure: {@code is_error: true} (an API error, a usage
     * limit) or an {@code error_*} subtype such as {@code error_max_turns} — the CLI ends with exit code
     * 0 in both cases (c6d1a872). Same rule as the ✗ of the live view ({@link ClaudeStreamEventFormatter}).
     */
    public boolean failed() {
        return Boolean.TRUE.equals(isError) || (subtype != null && subtype.startsWith("error"));
    }

    /** Whether a {@code result} line was found at all — the parser's fallback for plain text has neither field. */
    public boolean hasResultLine() {
        return subtype != null || isError != null;
    }

    public String model() {
        if (assistantModel != null && !assistantModel.isBlank()) {
            return assistantModel;
        }
        if (modelUsage == null || modelUsage.isEmpty()) {
            return null;
        }
        return modelUsage.size() == 1 ? modelUsage.keySet().iterator().next() : null;
    }

    public Map<String, Object> toMetadata() {
        var meta = new LinkedHashMap<String, Object>();
        if (usage != null) {
            meta.put("inputTokens", usage.inputTokens());
            meta.put("outputTokens", usage.outputTokens());
            usage.putCacheTokens(meta);
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
