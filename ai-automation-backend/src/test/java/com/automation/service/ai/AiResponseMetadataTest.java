package com.automation.service.ai;

import com.automation.service.cli.ClaudeJsonResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseMetadataTest {

    @Test
    void fromClaudeResponse_withFullResponse() {
        var usage = new ClaudeJsonResponse.Usage(1000, 500);
        var modelUsage = Map.of("claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(1000, 500, 0.05));
        var toolUses = List.of(
                new ClaudeJsonResponse.ToolUseEvent("read_file", Map.of("path", "/tmp/test")),
                new ClaudeJsonResponse.ToolUseEvent("write_file", Map.of("path", "/tmp/out"))
        );
        var parsed = new ClaudeJsonResponse("output text", "sess-123", 0.05, 2000L, 1500L, 3, usage, modelUsage, toolUses);

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.inputTokens()).isEqualTo(1000);
        assertThat(meta.outputTokens()).isEqualTo(500);
        assertThat(meta.costUsd()).isEqualTo(0.05);
        assertThat(meta.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(meta.estimated()).isFalse();
        assertThat(meta.durationApiMs()).isEqualTo(1500L);
        assertThat(meta.numTurns()).isEqualTo(3);
        assertThat(meta.sessionId()).isEqualTo("sess-123");
        assertThat(meta.toolNames()).containsExactly("read_file", "write_file");
    }

    @Test
    void fromClaudeResponse_prefersAssistantModel_whenModelUsageHasMultipleEntries() {
        var usage = new ClaudeJsonResponse.Usage(1000, 500);
        var modelUsage = Map.of(
                "claude-haiku-4-5-20251001", new ClaudeJsonResponse.ModelUsage(343, 11, 0.00039),
                "claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(1000, 500, 0.05)
        );
        var parsed = new ClaudeJsonResponse(
                "output text", "sess-123", 0.05, 2000L, 1500L, 3, usage, modelUsage, "claude-sonnet-4-6", List.of());

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void fromClaudeResponse_withNullReturnsEstimated() {
        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(null);

        assertThat(meta.inputTokens()).isZero();
        assertThat(meta.outputTokens()).isZero();
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.costUsd()).isNull();
        assertThat(meta.model()).isNull();
        assertThat(meta.toolNames()).isEmpty();
    }

    @Test
    void fromClaudeResponse_withNullUsageReturnsZeroTokens() {
        var parsed = new ClaudeJsonResponse("result", null, null, null, null, null, null, null, null);

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.inputTokens()).isZero();
        assertThat(meta.outputTokens()).isZero();
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.toolNames()).isEmpty();
    }

    @Test
    void fromClaudeResponse_withZeroTokensIsEstimated() {
        var usage = new ClaudeJsonResponse.Usage(0, 0);
        var parsed = new ClaudeJsonResponse("result", null, null, null, null, null, usage, null, null);

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.inputTokens()).isZero();
        assertThat(meta.outputTokens()).isZero();
        assertThat(meta.estimated()).isTrue();
    }

    @Test
    void fromClaudeResponse_withNonZeroTokensIsNotEstimated() {
        var usage = new ClaudeJsonResponse.Usage(100, 50);
        var parsed = new ClaudeJsonResponse("result", null, null, null, null, null, usage, null, null);

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.inputTokens()).isEqualTo(100);
        assertThat(meta.outputTokens()).isEqualTo(50);
        assertThat(meta.estimated()).isFalse();
    }

    @Test
    void fromClaudeResponse_withNullToolUsesReturnsEmptyList() {
        var usage = new ClaudeJsonResponse.Usage(10, 5);
        var parsed = new ClaudeJsonResponse("result", null, null, null, null, null, usage, null, null);

        AiResponseMetadata meta = AiResponseMetadata.fromClaudeResponse(parsed);

        assertThat(meta.toolNames()).isEmpty();
    }

    @Test
    void estimated_fromStrings() {
        AiResponseMetadata meta = AiResponseMetadata.estimated("Hello World!!", "OK");

        assertThat(meta.inputTokens()).isEqualTo(3); // 13 chars / 4 = 3
        assertThat(meta.outputTokens()).isEqualTo(0); // 2 chars / 4 = 0
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.costUsd()).isNull();
        assertThat(meta.model()).isNull();
    }

    @Test
    void estimated_fromNullStrings() {
        AiResponseMetadata meta = AiResponseMetadata.estimated((String) null, (String) null);

        assertThat(meta.inputTokens()).isZero();
        assertThat(meta.outputTokens()).isZero();
        assertThat(meta.estimated()).isTrue();
    }

    @Test
    void estimated_fromTokenCounts() {
        AiResponseMetadata meta = AiResponseMetadata.estimated(250, 100);

        assertThat(meta.inputTokens()).isEqualTo(250);
        assertThat(meta.outputTokens()).isEqualTo(100);
        assertThat(meta.estimated()).isTrue();
        assertThat(meta.costUsd()).isNull();
        assertThat(meta.model()).isNull();
        assertThat(meta.toolNames()).isEmpty();
    }

    @Test
    void toMap_withAllFields() {
        var meta = new AiResponseMetadata(1000, 500, 0.05, "claude-sonnet-4-6", false, 1500L, 3, "sess-123",
                List.of("read_file", "write_file"));

        Map<String, Object> map = meta.toMap();

        assertThat(map).containsEntry("inputTokens", 1000);
        assertThat(map).containsEntry("outputTokens", 500);
        assertThat(map).containsEntry("costUsd", 0.05);
        assertThat(map).containsEntry("model", "claude-sonnet-4-6");
        assertThat(map).doesNotContainKey("estimated");
        assertThat(map).containsEntry("apiDurationMs", 1500L);
        assertThat(map).containsEntry("numTurns", 3);
        assertThat(map).containsEntry("sessionId", "sess-123");
        assertThat(map).containsEntry("toolNames", List.of("read_file", "write_file"));
        assertThat(map).containsEntry("toolUseCount", 2);
    }

    @Test
    void toMap_estimatedFlagIncludedWhenTrue() {
        var meta = AiResponseMetadata.estimated(100, 50);

        Map<String, Object> map = meta.toMap();

        assertThat(map).containsEntry("estimated", true);
        assertThat(map).doesNotContainKey("costUsd");
        assertThat(map).doesNotContainKey("model");
        assertThat(map).doesNotContainKey("apiDurationMs");
        assertThat(map).doesNotContainKey("numTurns");
        assertThat(map).doesNotContainKey("sessionId");
        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }

    @Test
    void toMap_emptyToolListExcluded() {
        var meta = new AiResponseMetadata(100, 50, null, null, false, null, null, null, List.of());

        Map<String, Object> map = meta.toMap();

        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }

    @Test
    void toMap_nullToolListExcluded() {
        var meta = new AiResponseMetadata(100, 50, null, null, false, null, null, null, null);

        Map<String, Object> map = meta.toMap();

        assertThat(map).doesNotContainKey("toolNames");
        assertThat(map).doesNotContainKey("toolUseCount");
    }
}
