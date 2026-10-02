package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunListItemResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static WorkflowRunListItemResponse sample() {
        return new WorkflowRunListItemResponse(
                "run-1", "wf-1", "Nightly", "AutoPilot", "/repo",
                RunStatus.COMPLETED,
                Instant.parse("2026-09-20T01:00:00Z"), Instant.parse("2026-09-20T01:05:00Z"), 300_000L,
                4, Map.of("message", "none"), Map.of("k", "v"), Map.of("input", 1),
                "schedule-1", "hash", "version-1", TriggerSource.MANUAL,
                3, StepStatus.COMPLETED, "last output");
    }

    @Test
    void preview_length_is_five_hundred() {
        assertThat(WorkflowRunListItemResponse.PREVIEW_LENGTH).isEqualTo(500);
    }

    @Test
    void list_item_carries_no_steps_unlike_the_detail_response() {
        assertThat(Arrays.stream(WorkflowRunListItemResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("steps")
                .contains("stepCount", "lastStepStatus", "lastStepPreview");
        assertThat(Arrays.stream(WorkflowRunResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .contains("steps");
    }

    @Test
    void serializes_the_list_fields_for_the_runs_page() throws Exception {
        JsonNode json = MAPPER.valueToTree(sample());

        assertThat(json.has("steps")).isFalse();
        assertThat(json.get("stepCount").intValue()).isEqualTo(3);
        assertThat(json.get("lastStepStatus").textValue()).isEqualTo("COMPLETED");
        assertThat(json.get("lastStepPreview").textValue()).isEqualTo("last output");
        assertThat(json.get("startedAt").textValue()).isEqualTo("2026-09-20T01:00:00Z");
        assertThat(json.get("triggerSource").textValue()).isEqualTo("MANUAL");
        assertThat(json.get("error").get("message").textValue()).isEqualTo("none");
    }

    @Test
    void round_trips_through_json() throws Exception {
        WorkflowRunListItemResponse back =
                MAPPER.readValue(MAPPER.writeValueAsString(sample()), WorkflowRunListItemResponse.class);

        assertThat(back).isEqualTo(sample());
    }

    /** TODO 6db47bc2: die Liste nennt den Chain-Run; ältere Aufrufer (ohne das Feld) bekommen null. */
    @Test
    void serializes_the_chain_run_and_defaults_it_to_null() {
        WorkflowRunListItemResponse entry = new WorkflowRunListItemResponse(
                "run-3", "wf-1", null, null, null, RunStatus.COMPLETED, null, null, null, null,
                null, null, null, null, null, null, TriggerSource.CHAIN, 0, null, null, null, "chain-run-1");

        assertThat(MAPPER.valueToTree(entry).get("chainRunId").textValue()).isEqualTo("chain-run-1");
        assertThat(sample().chainRunId()).isNull();
        assertThat(MAPPER.valueToTree(sample()).get("chainRunId").isNull()).isTrue();
    }

    @Test
    void run_without_steps_keeps_last_step_fields_null() throws Exception {
        WorkflowRunListItemResponse empty = new WorkflowRunListItemResponse(
                "run-2", "wf-1", null, null, null, RunStatus.PENDING, null, null, null, null,
                null, null, null, null, null, null, null, 0, null, null);

        JsonNode json = MAPPER.valueToTree(empty);

        assertThat(json.get("stepCount").intValue()).isZero();
        assertThat(json.get("lastStepStatus").isNull()).isTrue();
        assertThat(json.get("lastStepPreview").isNull()).isTrue();
    }
}
