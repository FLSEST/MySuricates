package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowMemoryResponseTest {

    private static WorkflowMemoryResponse sample() {
        return new WorkflowMemoryResponse(
                "mem-1",
                "wf-1",
                "Sample title",
                "Sample content body",
                List.of("tag-a", "tag-b"),
                List.of("run-1", "run-2"),
                "ANTHROPIC",
                true,
                Instant.parse("2026-04-01T10:00:00Z")
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            WorkflowMemoryResponse response = sample();

            assertThat(response.id()).isEqualTo("mem-1");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.title()).isEqualTo("Sample title");
            assertThat(response.content()).isEqualTo("Sample content body");
            assertThat(response.tags()).containsExactly("tag-a", "tag-b");
            assertThat(response.sourceRunIds()).containsExactly("run-1", "run-2");
            assertThat(response.provider()).isEqualTo("ANTHROPIC");
            assertThat(response.active()).isTrue();
            assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-04-01T10:00:00Z"));
        }

        @Test
        void should_allow_null_reference_fields_when_constructed_with_minimal_values() {
            WorkflowMemoryResponse response = new WorkflowMemoryResponse(
                    null, null, null, null, null, null, null, false, null
            );

            assertThat(response.id()).isNull();
            assertThat(response.workflowId()).isNull();
            assertThat(response.title()).isNull();
            assertThat(response.content()).isNull();
            assertThat(response.tags()).isNull();
            assertThat(response.sourceRunIds()).isNull();
            assertThat(response.provider()).isNull();
            assertThat(response.active()).isFalse();
            assertThat(response.createdAt()).isNull();
        }

        @Test
        void should_accept_empty_collections_for_tags_and_sourceRunIds() {
            WorkflowMemoryResponse response = new WorkflowMemoryResponse(
                    "mem-1",
                    "wf-1",
                    "title",
                    "content",
                    List.of(),
                    List.of(),
                    "ANTHROPIC",
                    false,
                    Instant.parse("2026-04-01T10:00:00Z")
            );

            assertThat(response.tags()).isEmpty();
            assertThat(response.sourceRunIds()).isEmpty();
            assertThat(response.active()).isFalse();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            WorkflowMemoryResponse a = sample();
            WorkflowMemoryResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_one_field_differs() {
            WorkflowMemoryResponse a = sample();
            WorkflowMemoryResponse b = new WorkflowMemoryResponse(
                    "mem-2",
                    a.workflowId(),
                    a.title(),
                    a.content(),
                    a.tags(),
                    a.sourceRunIds(),
                    a.provider(),
                    a.active(),
                    a.createdAt()
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_not_be_equal_when_active_flag_differs() {
            WorkflowMemoryResponse a = sample();
            WorkflowMemoryResponse b = new WorkflowMemoryResponse(
                    a.id(),
                    a.workflowId(),
                    a.title(),
                    a.content(),
                    a.tags(),
                    a.sourceRunIds(),
                    a.provider(),
                    !a.active(),
                    a.createdAt()
            );

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_key_fields_in_toString() {
            WorkflowMemoryResponse response = sample();

            assertThat(response.toString())
                    .contains("mem-1")
                    .contains("wf-1")
                    .contains("Sample title")
                    .contains("ANTHROPIC");
        }
    }
}
