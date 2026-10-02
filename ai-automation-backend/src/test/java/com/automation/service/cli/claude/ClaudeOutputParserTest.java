package com.automation.service.cli.claude;

import com.automation.service.ai.AiResponseMetadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ClaudeOutputParserTest {

    @Test
    void parseStreamJson_parsesResultLine() {
        String ndjson = """
                {"type":"result","subtype":"success","result":"Hello world","session_id":"sess-123","total_cost_usd":0.004,"duration_ms":3200,"duration_api_ms":2800,"num_turns":1,"usage":{"input_tokens":100,"output_tokens":50},"modelUsage":{"claude-sonnet-4-6":{"inputTokens":100,"outputTokens":50,"costUSD":0.004}}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);

        assertThat(parsed.result()).isEqualTo("Hello world");
        assertThat(parsed.sessionId()).isEqualTo("sess-123");
        assertThat(parsed.totalCostUsd()).isEqualTo(0.004);
        assertThat(parsed.durationMs()).isEqualTo(3200L);
        assertThat(parsed.durationApiMs()).isEqualTo(2800L);
        assertThat(parsed.numTurns()).isEqualTo(1);
        assertThat(parsed.usage().inputTokens()).isEqualTo(100);
        assertThat(parsed.usage().outputTokens()).isEqualTo(50);
        assertThat(parsed.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void parseStreamJson_prefersAssistantModel_whenModelUsageContainsMultipleModels() {
        String ndjson = """
                {"type":"assistant","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"OK"}]}}
                {"type":"result","subtype":"success","result":"OK","session_id":"sess-123","total_cost_usd":0.055,"duration_ms":2304,"duration_api_ms":3505,"num_turns":1,"usage":{"input_tokens":3,"output_tokens":4},"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":343,"outputTokens":11,"costUSD":0.00039},"claude-sonnet-4-6":{"inputTokens":3,"outputTokens":4,"costUSD":0.0546}}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);

        assertThat(parsed.assistantModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(parsed.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void parseStreamJson_extractsToolUseEvents() {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{"file_path":"src/main.ts"}},{"type":"tool_use","name":"Edit","input":{"file_path":"src/main.ts","old":"a","new":"b"}}]}}
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"npm test"}}]}}
                {"type":"result","subtype":"success","result":"Done.","total_cost_usd":0.008,"usage":{"input_tokens":1500,"output_tokens":800}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);

        assertThat(parsed.result()).isEqualTo("Done.");
        assertThat(parsed.toolUses()).hasSize(3);
        assertThat(parsed.toolUses().get(0).name()).isEqualTo("Read");
        assertThat(parsed.toolUses().get(1).name()).isEqualTo("Edit");
        assertThat(parsed.toolUses().get(2).name()).isEqualTo("Bash");
        assertThat(parsed.toolUses().get(0).input()).containsEntry("file_path", "src/main.ts");
    }

    @Test
    void parseStreamJson_toMetadataIncludesToolInfo() {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read","input":{}},{"type":"tool_use","name":"Bash","input":{}}]}}
                {"type":"result","subtype":"success","result":"ok","total_cost_usd":0.01,"duration_api_ms":1500,"num_turns":2,"session_id":"s1","usage":{"input_tokens":200,"output_tokens":100},"modelUsage":{"claude-sonnet-4-6":{"inputTokens":200,"outputTokens":100,"costUSD":0.01}}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);
        var meta = parsed.toMetadata();

        assertThat(meta).containsEntry("inputTokens", 200);
        assertThat(meta).containsEntry("outputTokens", 100);
        assertThat(meta).containsEntry("costUsd", 0.01);
        assertThat(meta).containsEntry("apiDurationMs", 1500L);
        assertThat(meta).containsEntry("numTurns", 2);
        assertThat(meta).containsEntry("model", "claude-sonnet-4-6");
        assertThat(meta).containsEntry("sessionId", "s1");
        assertThat(meta).containsEntry("toolUseCount", 2);
        assertThat(meta.get("toolNames")).asList().containsExactly("Read", "Bash");
    }

    @Test
    void parseStreamJson_fallbackOnInvalidJson() {
        String raw = "This is just plain text, not JSON";

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(raw);

        assertThat(parsed.result()).isEqualTo(raw);
        assertThat(parsed.totalCostUsd()).isNull();
        assertThat(parsed.usage()).isNull();
    }

    @Test
    void parseStreamJson_fallbackOnEmptyInput() {
        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson("");
        assertThat(parsed.result()).isEmpty();

        ClaudeJsonResponse parsedNull = ClaudeOutputParser.parseStreamJson(null);
        assertThat(parsedNull.result()).isEmpty();
    }

    @Test
    void parseStreamJson_skipsMalformedLines() {
        String ndjson = """
                not-json-line
                {"type":"result","result":"Good result","total_cost_usd":0.001,"usage":{"input_tokens":10,"output_tokens":5}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);
        assertThat(parsed.result()).isEqualTo("Good result");
        assertThat(parsed.totalCostUsd()).isEqualTo(0.001);
    }

    @Test
    void parseStreamJson_handlesAssistantWithoutToolUse() {
        String ndjson = """
                {"type":"assistant","message":{"content":[{"type":"text","text":"thinking..."}]}}
                {"type":"result","result":"final answer","usage":{"input_tokens":50,"output_tokens":25}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);
        assertThat(parsed.result()).isEqualTo("final answer");
        assertThat(parsed.toolUses()).isNull();
    }

    /**
     * A recorded audit step (run 60fc8d09, step 3, CLI 2.1.x): its step metadata said
     * {@code inputTokens: 2}, because {@code input_tokens} counts only what the prompt cache did not
     * serve. The numbers are the step's own ({@code usage} of its session, {@code modelUsage} from the
     * CLI's cost record); the line layout follows the result schema of the CLI (TODO 20adda30).
     */
    private static final String RECORDED_AUDIT_STEP = """
            {"type":"system","subtype":"init","cwd":"/work/project","session_id":"dbfa497d-127c-41e5-b294-bda24688f2ec","tools":["Bash","Read"],"mcp_servers":[],"model":"claude-opus-5-5","permissionMode":"default","slash_commands":[],"apiKeySource":"none","claude_code_version":"2.1.283","output_style":"default","agents":[],"skills":[],"plugins":[],"uuid":"u-init"}
            {"type":"assistant","message":{"model":"claude-opus-5-5","id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"gate_status: GREEN"}],"stop_reason":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":48462,"cache_read_input_tokens":13720,"output_tokens":918,"output_tokens_details":{"thinking_tokens":377},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":48462,"ephemeral_5m_input_tokens":0}}},"parent_tool_use_id":null,"session_id":"dbfa497d-127c-41e5-b294-bda24688f2ec","uuid":"u-1"}
            {"type":"result","subtype":"success","is_error":false,"duration_ms":13043,"duration_api_ms":8794,"num_turns":1,"result":"gate_status: GREEN","stop_reason":"end_turn","session_id":"dbfa497d-127c-41e5-b294-bda24688f2ec","total_cost_usd":0.408808,"usage":{"input_tokens":2,"cache_creation_input_tokens":48462,"cache_read_input_tokens":13720,"output_tokens":918,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":48462,"ephemeral_5m_input_tokens":0},"output_tokens_details":{"thinking_tokens":377}},"modelUsage":{"claude-opus-5-5":{"inputTokens":2,"outputTokens":918,"thinkingTokens":377,"cacheReadInputTokens":13720,"cacheCreationInputTokens":48462,"webSearchRequests":0,"costUSD":0.408808}},"permission_denials":[],"uuid":"u-result"}
            """;

    @Test
    void parseStreamJson_recordedAuditStep_keepsTheCacheTokensBesideTheUncachedInput() {
        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(RECORDED_AUDIT_STEP);

        assertThat(parsed.result()).isEqualTo("gate_status: GREEN");
        assertThat(parsed.usage().inputTokens()).isEqualTo(2);
        assertThat(parsed.usage().cacheReadInputTokens()).isEqualTo(13720);
        assertThat(parsed.usage().cacheCreationInputTokens()).isEqualTo(48462);
        assertThat(parsed.usage().outputTokens()).isEqualTo(918);
        assertThat(parsed.toMetadata())
                .containsEntry("inputTokens", 2)
                .containsEntry("cacheReadInputTokens", 13720)
                .containsEntry("cacheCreationInputTokens", 48462)
                .containsEntry("outputTokens", 918)
                .containsEntry("costUsd", 0.408808)
                .containsEntry("model", "claude-opus-5-5");
    }

    /** TODO 20861200: the thinking part of the output, from {@code output_tokens_details} — inside outputTokens. */
    @Test
    void parseStreamJson_recordedAuditStep_reportsTheThinkingTokens() {
        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(RECORDED_AUDIT_STEP);

        assertThat(parsed.usage().thinkingTokens()).isEqualTo(377);
        assertThat(parsed.toMetadata()).containsEntry("thinkingTokens", 377).containsEntry("outputTokens", 918);
        assertThat(AiResponseMetadata.fromClaudeResponse(parsed).toMap()).containsEntry("thinkingTokens", 377);
    }

    @Test
    void parseStreamJson_resultWithoutCacheFields_reportsNoCacheTokens() {
        String ndjson = """
                {"type":"result","subtype":"success","result":"ok","usage":{"input_tokens":100,"output_tokens":50}}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);

        assertThat(parsed.usage().cacheReadInputTokens()).isNull();
        assertThat(parsed.usage().cacheCreationInputTokens()).isNull();
        assertThat(parsed.usage().thinkingTokens()).isNull();
        assertThat(parsed.toMetadata()).doesNotContainKeys("cacheReadInputTokens", "cacheCreationInputTokens", "thinkingTokens");
    }

    @Test
    void extractText_returnsResultText() {
        String ndjson = """
                {"type":"result","result":"The answer is 42","usage":{"input_tokens":10,"output_tokens":5}}
                """;

        String text = ClaudeOutputParser.extractText(ndjson);
        assertThat(text).isEqualTo("The answer is 42");
    }

    @Test
    void extractText_fallbackOnPlainText() {
        String text = ClaudeOutputParser.extractText("just plain text");
        assertThat(text).isEqualTo("just plain text");
    }

    @Test
    void extractText_handlesNullAndEmpty() {
        assertThat(ClaudeOutputParser.extractText(null)).isEmpty();
        assertThat(ClaudeOutputParser.extractText("")).isEmpty();
    }

    // --- is_error / subtype of the result line (c6d1a872) ---

    @Test
    void parseStreamJson_keepsIsErrorAndSubtype() {
        String ndjson = """
                {"type":"assistant","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"..."}]}}
                {"type":"result","subtype":"error_max_turns","is_error":false,"num_turns":3,"session_id":"s"}
                """;

        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(ndjson);

        assertThat(parsed.subtype()).isEqualTo("error_max_turns");
        assertThat(parsed.isError()).isFalse();
        assertThat(parsed.failed()).isTrue();
        assertThat(parsed.hasResultLine()).isTrue();
        assertThat(parsed.result()).isNull();
    }

    @Test
    void parseStreamJson_successLine_isNotFailed() {
        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\"}");

        assertThat(parsed.failed()).isFalse();
        assertThat(parsed.hasResultLine()).isTrue();
    }

    @Test
    void parseStreamJson_plainText_hasNoResultLine() {
        ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson("Invalid API key · Please run /login");

        assertThat(parsed.failed()).isFalse();
        assertThat(parsed.hasResultLine()).isFalse();
        assertThat(parsed.result()).isEqualTo("Invalid API key · Please run /login");
    }
}
