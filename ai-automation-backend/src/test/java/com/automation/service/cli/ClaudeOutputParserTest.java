package com.automation.service.cli;

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
}
