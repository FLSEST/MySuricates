package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BrokenWorkflowResponseTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-03T08:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-03T08:00:05Z");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static BrokenWorkflowResponse sample() {
        return new BrokenWorkflowResponse(
                "wf-abc",
                "My Workflow",
                "My Project",
                "run-xyz",
                RunStatus.FAILED,
                "Block execution failed",
                2,
                "shell",
                STARTED_AT,
                COMPLETED_AT,
                5000L,
                "sched-001"
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_populate_all_fields_when_constructed() {
            BrokenWorkflowResponse r = sample();

            assertThat(r.workflowId()).isEqualTo("wf-abc");
            assertThat(r.workflowName()).isEqualTo("My Workflow");
            assertThat(r.projectName()).isEqualTo("My Project");
            assertThat(r.runId()).isEqualTo("run-xyz");
            assertThat(r.status()).isEqualTo(RunStatus.FAILED);
            assertThat(r.errorMessage()).isEqualTo("Block execution failed");
            assertThat(r.failedStepIndex()).isEqualTo(2);
            assertThat(r.failedBlockType()).isEqualTo("shell");
            assertThat(r.startedAt()).isEqualTo(STARTED_AT);
            assertThat(r.completedAt()).isEqualTo(COMPLETED_AT);
            assertThat(r.durationMs()).isEqualTo(5000L);
            assertThat(r.triggeredByScheduleId()).isEqualTo("sched-001");
        }

        @Test
        void should_tolerate_null_values_when_constructed_with_nulls() {
            BrokenWorkflowResponse r = new BrokenWorkflowResponse(
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null
            );

            assertThat(r.workflowId()).isNull();
            assertThat(r.workflowName()).isNull();
            assertThat(r.projectName()).isNull();
            assertThat(r.runId()).isNull();
            assertThat(r.status()).isNull();
            assertThat(r.errorMessage()).isNull();
            assertThat(r.failedStepIndex()).isNull();
            assertThat(r.failedBlockType()).isNull();
            assertThat(r.startedAt()).isNull();
            assertThat(r.completedAt()).isNull();
            assertThat(r.durationMs()).isNull();
            assertThat(r.triggeredByScheduleId()).isNull();
        }
    }

    @Nested
    class JacksonSerialization {

        @Test
        void should_serialize_to_json_when_written_with_jackson() throws Exception {
            String json = MAPPER.writeValueAsString(sample());

            assertThat(json)
                    .contains("\"workflowId\"")
                    .contains("\"wf-abc\"")
                    .contains("\"workflowName\"")
                    .contains("\"My Workflow\"")
                    .contains("\"projectName\"")
                    .contains("\"runId\"")
                    .contains("\"status\"")
                    .contains("\"FAILED\"")
                    .contains("\"errorMessage\"")
                    .contains("\"Block execution failed\"")
                    .contains("\"failedStepIndex\"")
                    .contains("\"failedBlockType\"")
                    .contains("\"shell\"")
                    .contains("\"durationMs\"")
                    .contains("\"triggeredByScheduleId\"");
        }

        @Test
        void should_roundtrip_through_jackson_when_deserialized() throws Exception {
            BrokenWorkflowResponse original = sample();
            String json = MAPPER.writeValueAsString(original);
            BrokenWorkflowResponse deserialized = MAPPER.readValue(json, BrokenWorkflowResponse.class);

            assertThat(deserialized).isEqualTo(original);
        }
    }

    @Nested
    class ErrorStateSemantics {

        @Test
        void should_expose_error_state_when_latest_run_failed() {
            BrokenWorkflowResponse r = new BrokenWorkflowResponse(
                    "wf-broken",
                    "Broken Workflow",
                    "Ops Project",
                    "run-failed-42",
                    RunStatus.FAILED,
                    "Block execution failed",
                    3,
                    "shell",
                    STARTED_AT,
                    COMPLETED_AT,
                    5000L,
                    null
            );

            assertThat(r.status()).isEqualTo(RunStatus.FAILED);
            assertThat(r.errorMessage()).isEqualTo("Block execution failed");
            assertThat(r.failedStepIndex()).isEqualTo(3);
            assertThat(r.failedBlockType()).isEqualTo("shell");
            // These fields provide the full error context without loading step blobs
            assertThat(r.workflowId()).isNotNull();
            assertThat(r.runId()).isNotNull();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_components_match() {
            BrokenWorkflowResponse a = sample();
            BrokenWorkflowResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_run_id_differs() {
            BrokenWorkflowResponse a = sample();
            BrokenWorkflowResponse different = new BrokenWorkflowResponse(
                    a.workflowId(), a.workflowName(), a.projectName(),
                    "run-other",
                    a.status(), a.errorMessage(), a.failedStepIndex(), a.failedBlockType(),
                    a.startedAt(), a.completedAt(), a.durationMs(), a.triggeredByScheduleId()
            );

            assertThat(a).isNotEqualTo(different);
        }
    }
}
