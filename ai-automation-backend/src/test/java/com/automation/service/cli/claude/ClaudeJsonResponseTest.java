package com.automation.service.cli.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeJsonResponseTest {

    @Nested
    class ModelTests {

        @Test
        void should_returnNull_when_modelUsageIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);
            assertThat(response.model()).isNull();
        }

        @Test
        void should_returnNull_when_modelUsageIsEmpty() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, Map.of(), null);
            assertThat(response.model()).isNull();
        }

        @Test
        void should_returnFirstKey_when_modelUsageHasEntries() {
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(100, 200, 0.05));
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, modelUsage, null);
            assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        }

        @Test
        void should_returnAssistantModel_when_present() {
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-opus-4-6", new ClaudeJsonResponse.ModelUsage(500, 1000, 0.50));
            modelUsage.put("claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(100, 200, 0.05));
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, modelUsage, "claude-sonnet-4-6", null);
            assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        }

        @Test
        void should_returnNull_when_modelUsageHasMultipleEntriesAndNoAssistantModel() {
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-opus-4-6", new ClaudeJsonResponse.ModelUsage(500, 1000, 0.50));
            modelUsage.put("claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(100, 200, 0.05));
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, modelUsage, null);
            assertThat(response.model()).isNull();
        }
    }

    @Nested
    class ToMetadataTests {

        @Test
        void should_returnEmptyMap_when_allFieldsAreNull() {
            var response = new ClaudeJsonResponse(
                    null, null, null, null, null, null, null, null, null);
            assertThat(response.toMetadata()).isEmpty();
        }

        @Test
        void should_includeTokens_when_usageIsSet() {
            var usage = new ClaudeJsonResponse.Usage(150, 300);
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, usage, null, null);

            Map<String, Object> meta = response.toMetadata();
            assertThat(meta).containsEntry("inputTokens", 150);
            assertThat(meta).containsEntry("outputTokens", 300);
        }

        @Test
        void should_excludeTokens_when_usageIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            Map<String, Object> meta = response.toMetadata();
            assertThat(meta).doesNotContainKey("inputTokens");
            assertThat(meta).doesNotContainKey("outputTokens");
        }

        @Test
        void should_includeCostUsd_when_totalCostUsdIsSet() {
            var response = new ClaudeJsonResponse(
                    "result", null, 0.042, null, null, null, null, null, null);

            assertThat(response.toMetadata()).containsEntry("costUsd", 0.042);
        }

        @Test
        void should_excludeCostUsd_when_totalCostUsdIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("costUsd");
        }

        @Test
        void should_includeApiDurationMs_when_durationApiMsIsSet() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, 1500L, null, null, null, null);

            assertThat(response.toMetadata()).containsEntry("apiDurationMs", 1500L);
        }

        @Test
        void should_excludeApiDurationMs_when_durationApiMsIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("apiDurationMs");
        }

        @Test
        void should_includeNumTurns_when_numTurnsIsSet() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, 3, null, null, null);

            assertThat(response.toMetadata()).containsEntry("numTurns", 3);
        }

        @Test
        void should_excludeNumTurns_when_numTurnsIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("numTurns");
        }

        @Test
        void should_includeModel_when_modelUsageIsPresent() {
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-haiku-4-5", new ClaudeJsonResponse.ModelUsage(50, 100, 0.01));
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, modelUsage, null);

            assertThat(response.toMetadata()).containsEntry("model", "claude-haiku-4-5");
        }

        @Test
        void should_includeAssistantModel_when_modelUsageHasMultipleEntries() {
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-haiku-4-5", new ClaudeJsonResponse.ModelUsage(50, 100, 0.01));
            modelUsage.put("claude-sonnet-4-6", new ClaudeJsonResponse.ModelUsage(100, 200, 0.05));
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, modelUsage, "claude-sonnet-4-6", null);

            assertThat(response.toMetadata()).containsEntry("model", "claude-sonnet-4-6");
        }

        @Test
        void should_excludeModel_when_modelUsageIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("model");
        }

        @Test
        void should_includeSessionId_when_sessionIdIsSet() {
            var response = new ClaudeJsonResponse(
                    "result", "sess-abc-123", null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).containsEntry("sessionId", "sess-abc-123");
        }

        @Test
        void should_excludeSessionId_when_sessionIdIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("sessionId");
        }

        @Test
        void should_includeToolInfo_when_toolUsesIsNonEmpty() {
            var toolUses = List.of(
                    new ClaudeJsonResponse.ToolUseEvent("Read", Map.of("path", "/tmp/file.txt")),
                    new ClaudeJsonResponse.ToolUseEvent("Write", Map.of("content", "hello"))
            );
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, toolUses);

            Map<String, Object> meta = response.toMetadata();
            assertThat(meta).containsEntry("toolUseCount", 2);
            assertThat(meta).containsKey("toolNames");
            @SuppressWarnings("unchecked")
            List<String> toolNames = (List<String>) meta.get("toolNames");
            assertThat(toolNames).containsExactly("Read", "Write");
        }

        @Test
        void should_excludeToolInfo_when_toolUsesIsNull() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("toolNames");
            assertThat(response.toMetadata()).doesNotContainKey("toolUseCount");
        }

        @Test
        void should_excludeToolInfo_when_toolUsesIsEmpty() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, null, null, Collections.emptyList());

            assertThat(response.toMetadata()).doesNotContainKey("toolNames");
            assertThat(response.toMetadata()).doesNotContainKey("toolUseCount");
        }

        @Test
        void should_includeAllFields_when_fullyPopulated() {
            var usage = new ClaudeJsonResponse.Usage(500, 1000);
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-opus-4-6", new ClaudeJsonResponse.ModelUsage(500, 1000, 0.75));
            var toolUses = List.of(
                    new ClaudeJsonResponse.ToolUseEvent("Bash", Map.of("command", "ls"))
            );

            var response = new ClaudeJsonResponse(
                    "Full result", "sess-xyz", 0.75, 5000L, 3000L, 5, usage, modelUsage, toolUses);

            Map<String, Object> meta = response.toMetadata();
            assertThat(meta)
                    .containsEntry("inputTokens", 500)
                    .containsEntry("outputTokens", 1000)
                    .containsEntry("costUsd", 0.75)
                    .containsEntry("apiDurationMs", 3000L)
                    .containsEntry("numTurns", 5)
                    .containsEntry("model", "claude-opus-4-6")
                    .containsEntry("sessionId", "sess-xyz")
                    .containsEntry("toolUseCount", 1);
            assertThat(meta).hasSize(9); // 8 entries + toolNames
        }
    }

    @Nested
    class RecordAccessorTests {

        @Test
        void should_returnAllFieldsViaAccessors() {
            var usage = new ClaudeJsonResponse.Usage(10, 20);
            var modelUsage = Map.of("model-1", new ClaudeJsonResponse.ModelUsage(10, 20, 0.01));
            var toolUses = List.of(new ClaudeJsonResponse.ToolUseEvent("tool", Map.of()));

            var response = new ClaudeJsonResponse(
                    "res", "sid", 1.5, 2000L, 1000L, 2, usage, modelUsage, toolUses);

            assertThat(response.result()).isEqualTo("res");
            assertThat(response.sessionId()).isEqualTo("sid");
            assertThat(response.totalCostUsd()).isEqualTo(1.5);
            assertThat(response.durationMs()).isEqualTo(2000L);
            assertThat(response.durationApiMs()).isEqualTo(1000L);
            assertThat(response.numTurns()).isEqualTo(2);
            assertThat(response.usage()).isEqualTo(usage);
            assertThat(response.modelUsage()).isEqualTo(modelUsage);
            assertThat(response.toolUses()).isEqualTo(toolUses);
        }
    }

    @Nested
    class JsonDeserializationTests {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void should_deserializeFullResponse_withSnakeCaseProperties() throws Exception {
            var json = """
                {
                    "result": "Hello world",
                    "session_id": "sess-123",
                    "total_cost_usd": 0.042,
                    "duration_ms": 5000,
                    "duration_api_ms": 3000,
                    "num_turns": 2,
                    "usage": {"input_tokens": 150, "output_tokens": 300},
                    "modelUsage": {
                        "claude-sonnet-4-6": {"inputTokens": 150, "outputTokens": 300, "costUSD": 0.042}
                    },
                    "toolUses": [{"name": "Read", "input": {"path": "/tmp/test.txt"}}]
                }
                """;

            var response = mapper.readValue(json, ClaudeJsonResponse.class);

            assertThat(response.result()).isEqualTo("Hello world");
            assertThat(response.sessionId()).isEqualTo("sess-123");
            assertThat(response.totalCostUsd()).isEqualTo(0.042);
            assertThat(response.durationMs()).isEqualTo(5000L);
            assertThat(response.durationApiMs()).isEqualTo(3000L);
            assertThat(response.numTurns()).isEqualTo(2);
            assertThat(response.usage().inputTokens()).isEqualTo(150);
            assertThat(response.usage().outputTokens()).isEqualTo(300);
            assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(response.toolUses()).hasSize(1);
            assertThat(response.toolUses().get(0).name()).isEqualTo("Read");
        }

        @Test
        void should_ignoreUnknownProperties() throws Exception {
            var json = """
                {
                    "result": "test",
                    "unknown_field": "should be ignored",
                    "another_unknown": 42
                }
                """;

            var response = mapper.readValue(json, ClaudeJsonResponse.class);

            assertThat(response.result()).isEqualTo("test");
            assertThat(response.sessionId()).isNull();
        }

        @Test
        void should_deserializeMinimalResponse() throws Exception {
            var json = """
                {"result": "ok"}
                """;

            var response = mapper.readValue(json, ClaudeJsonResponse.class);

            assertThat(response.result()).isEqualTo("ok");
            assertThat(response.sessionId()).isNull();
            assertThat(response.totalCostUsd()).isNull();
            assertThat(response.usage()).isNull();
            assertThat(response.modelUsage()).isNull();
            assertThat(response.toolUses()).isNull();
        }

        @Test
        void should_deserializeUsage_withSnakeCaseTokenFields() throws Exception {
            var json = """
                {"input_tokens": 500, "output_tokens": 1000}
                """;

            var usage = mapper.readValue(json, ClaudeJsonResponse.Usage.class);

            assertThat(usage.inputTokens()).isEqualTo(500);
            assertThat(usage.outputTokens()).isEqualTo(1000);
        }

        @Test
        void should_ignoreUnknownUsageFields() throws Exception {
            var json = """
                {"input_tokens": 100, "output_tokens": 200, "cache_tokens": 50}
                """;

            var usage = mapper.readValue(json, ClaudeJsonResponse.Usage.class);

            assertThat(usage.inputTokens()).isEqualTo(100);
            assertThat(usage.outputTokens()).isEqualTo(200);
        }
    }

    @Nested
    class MetadataEdgeCaseTests {

        @Test
        void should_excludeDurationMs_fromMetadata() {
            var response = new ClaudeJsonResponse(
                    "result", null, null, 5000L, null, null, null, null, null);

            assertThat(response.toMetadata()).doesNotContainKey("durationMs");
            assertThat(response.toMetadata()).doesNotContainKey("duration_ms");
        }

        @Test
        void should_includeZeroTokens_when_usageIsNonNull() {
            var usage = new ClaudeJsonResponse.Usage(0, 0);
            var response = new ClaudeJsonResponse(
                    "result", null, null, null, null, null, usage, null, null);

            Map<String, Object> meta = response.toMetadata();
            assertThat(meta).containsEntry("inputTokens", 0);
            assertThat(meta).containsEntry("outputTokens", 0);
        }

        @Test
        void should_preserveInsertionOrder_inMetadata() {
            var usage = new ClaudeJsonResponse.Usage(100, 200);
            var modelUsage = new LinkedHashMap<String, ClaudeJsonResponse.ModelUsage>();
            modelUsage.put("claude-opus-4-6", new ClaudeJsonResponse.ModelUsage(100, 200, 0.5));

            var response = new ClaudeJsonResponse(
                    "result", "sid", 0.5, null, 1000L, 3, usage, modelUsage, null);

            var keys = response.toMetadata().keySet().stream().toList();
            assertThat(keys).containsExactly(
                    "inputTokens", "outputTokens", "costUsd",
                    "apiDurationMs", "numTurns", "model", "sessionId");
        }
    }

    @Nested
    class RecordEqualityTests {

        @Test
        void should_beEqual_when_sameValues() {
            var usage = new ClaudeJsonResponse.Usage(100, 200);
            var r1 = new ClaudeJsonResponse("res", "sid", 1.0, 100L, 50L, 1, usage, null, null);
            var r2 = new ClaudeJsonResponse("res", "sid", 1.0, 100L, 50L, 1, usage, null, null);

            assertThat(r1).isEqualTo(r2);
            assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        }

        @Test
        void should_notBeEqual_when_differentValues() {
            var r1 = new ClaudeJsonResponse("a", null, null, null, null, null, null, null, null);
            var r2 = new ClaudeJsonResponse("b", null, null, null, null, null, null, null, null);

            assertThat(r1).isNotEqualTo(r2);
        }

        @Test
        void should_haveConsistentEquality_forInnerRecords() {
            var u1 = new ClaudeJsonResponse.Usage(10, 20);
            var u2 = new ClaudeJsonResponse.Usage(10, 20);
            assertThat(u1).isEqualTo(u2);

            var m1 = new ClaudeJsonResponse.ModelUsage(10, 20, 0.5);
            var m2 = new ClaudeJsonResponse.ModelUsage(10, 20, 0.5);
            assertThat(m1).isEqualTo(m2);

            var t1 = new ClaudeJsonResponse.ToolUseEvent("Read", Map.of("k", "v"));
            var t2 = new ClaudeJsonResponse.ToolUseEvent("Read", Map.of("k", "v"));
            assertThat(t1).isEqualTo(t2);
        }
    }

    @Nested
    class InnerRecordTests {

        @Test
        void should_createUsageRecord() {
            var usage = new ClaudeJsonResponse.Usage(100, 200);
            assertThat(usage.inputTokens()).isEqualTo(100);
            assertThat(usage.outputTokens()).isEqualTo(200);
        }

        @Test
        void should_createModelUsageRecord() {
            var modelUsage = new ClaudeJsonResponse.ModelUsage(300, 600, 0.123);
            assertThat(modelUsage.inputTokens()).isEqualTo(300);
            assertThat(modelUsage.outputTokens()).isEqualTo(600);
            assertThat(modelUsage.costUSD()).isEqualTo(0.123);
        }

        @Test
        void should_createToolUseEventRecord() {
            var input = Map.<String, Object>of("key", "value");
            var event = new ClaudeJsonResponse.ToolUseEvent("Edit", input);
            assertThat(event.name()).isEqualTo("Edit");
            assertThat(event.input()).containsEntry("key", "value");
        }
    }
}
