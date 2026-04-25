package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityLogResponseTest {

    private static ActivityLogResponse sample() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user", "alice");
        metadata.put("count", 42);
        return new ActivityLogResponse(
                "log-123",
                Instant.parse("2026-04-25T10:15:30Z"),
                "INFO",
                "WORKFLOW",
                "Workflow started",
                "WorkflowExecutor",
                "ref-456",
                "wf-789",
                metadata,
                Instant.parse("2026-04-25T10:15:31Z")
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            ActivityLogResponse response = sample();

            assertThat(response.id()).isEqualTo("log-123");
            assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-04-25T10:15:30Z"));
            assertThat(response.level()).isEqualTo("INFO");
            assertThat(response.category()).isEqualTo("WORKFLOW");
            assertThat(response.message()).isEqualTo("Workflow started");
            assertThat(response.source()).isEqualTo("WorkflowExecutor");
            assertThat(response.referenceId()).isEqualTo("ref-456");
            assertThat(response.workflowId()).isEqualTo("wf-789");
            assertThat(response.metadata()).containsEntry("user", "alice").containsEntry("count", 42);
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-04-25T10:15:31Z"));
        }

        @Test
        void should_allow_null_fields_when_constructed_without_values() {
            ActivityLogResponse response = new ActivityLogResponse(
                    null, null, null, null, null, null, null, null, null, null
            );

            assertThat(response.id()).isNull();
            assertThat(response.timestamp()).isNull();
            assertThat(response.level()).isNull();
            assertThat(response.category()).isNull();
            assertThat(response.message()).isNull();
            assertThat(response.source()).isNull();
            assertThat(response.referenceId()).isNull();
            assertThat(response.workflowId()).isNull();
            assertThat(response.metadata()).isNull();
            assertThat(response.createdAt()).isNull();
        }

        @Test
        void should_accept_empty_metadata_map_when_no_metadata_present() {
            ActivityLogResponse response = new ActivityLogResponse(
                    "id", Instant.now(), "DEBUG", "SYS", "msg",
                    "src", "ref", "wf", Map.of(), Instant.now()
            );

            assertThat(response.metadata()).isEmpty();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            ActivityLogResponse a = sample();
            ActivityLogResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_a_field_differs() {
            ActivityLogResponse a = sample();
            ActivityLogResponse b = new ActivityLogResponse(
                    "different-id",
                    a.timestamp(),
                    a.level(),
                    a.category(),
                    a.message(),
                    a.source(),
                    a.referenceId(),
                    a.workflowId(),
                    a.metadata(),
                    a.createdAt()
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_all_fields_in_toString() {
            String s = sample().toString();

            assertThat(s).contains("log-123")
                    .contains("INFO")
                    .contains("WORKFLOW")
                    .contains("Workflow started")
                    .contains("WorkflowExecutor")
                    .contains("ref-456")
                    .contains("wf-789");
        }
    }
}
