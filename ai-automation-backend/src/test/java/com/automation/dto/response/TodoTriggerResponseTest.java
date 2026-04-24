package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoTriggerResponseTest {

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_store_all_fields_when_fully_populated() {
            Map<String, Object> contextTemplate = new HashMap<>();
            contextTemplate.put("key1", "value1");
            contextTemplate.put("key2", 42);
            Instant lastTriggered = Instant.parse("2026-01-15T10:00:00Z");
            Instant created = Instant.parse("2026-01-01T00:00:00Z");

            TodoTriggerResponse response = new TodoTriggerResponse(
                    "trigger-id-1",
                    "bug/*",
                    "project-1",
                    "AI",
                    "HIGH",
                    "workflow-123",
                    "Fix Bug Workflow",
                    contextTemplate,
                    true,
                    3,
                    true,
                    false,
                    "Triggers on high priority bugs",
                    lastTriggered,
                    7L,
                    created
            );

            assertThat(response.id()).isEqualTo("trigger-id-1");
            assertThat(response.tagPattern()).isEqualTo("bug/*");
            assertThat(response.projectId()).isEqualTo("project-1");
            assertThat(response.sourceFilter()).isEqualTo("AI");
            assertThat(response.minPriority()).isEqualTo("HIGH");
            assertThat(response.targetWorkflowId()).isEqualTo("workflow-123");
            assertThat(response.targetWorkflowName()).isEqualTo("Fix Bug Workflow");
            assertThat(response.contextTemplate()).isEqualTo(contextTemplate);
            assertThat(response.enabled()).isTrue();
            assertThat(response.maxAttempts()).isEqualTo(3);
            assertThat(response.autoClose()).isTrue();
            assertThat(response.processBacklog()).isFalse();
            assertThat(response.description()).isEqualTo("Triggers on high priority bugs");
            assertThat(response.lastTriggeredAt()).isEqualTo(lastTriggered);
            assertThat(response.triggerCount()).isEqualTo(7L);
            assertThat(response.createdAt()).isEqualTo(created);
        }

        @Test
        void should_allow_null_values_for_optional_fields() {
            TodoTriggerResponse response = new TodoTriggerResponse(
                    null, null, null, null, null, null, null,
                    null, false, 0, false, false, null, null, 0L, null
            );

            assertThat(response.id()).isNull();
            assertThat(response.tagPattern()).isNull();
            assertThat(response.projectId()).isNull();
            assertThat(response.sourceFilter()).isNull();
            assertThat(response.minPriority()).isNull();
            assertThat(response.targetWorkflowId()).isNull();
            assertThat(response.targetWorkflowName()).isNull();
            assertThat(response.contextTemplate()).isNull();
            assertThat(response.enabled()).isFalse();
            assertThat(response.maxAttempts()).isZero();
            assertThat(response.autoClose()).isFalse();
            assertThat(response.processBacklog()).isFalse();
            assertThat(response.description()).isNull();
            assertThat(response.lastTriggeredAt()).isNull();
            assertThat(response.triggerCount()).isZero();
            assertThat(response.createdAt()).isNull();
        }

        @Test
        void should_handle_empty_context_template() {
            TodoTriggerResponse response = new TodoTriggerResponse(
                    "id", "tag", "proj", "USER", "LOW", "wf", "Name",
                    Map.of(), true, 1, false, true, "desc",
                    Instant.EPOCH, 0L, Instant.EPOCH
            );

            assertThat(response.contextTemplate()).isNotNull().isEmpty();
            assertThat(response.processBacklog()).isTrue();
            assertThat(response.autoClose()).isFalse();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            Instant now = Instant.parse("2026-04-25T12:00:00Z");
            Map<String, Object> ctx = Map.of("k", "v");

            TodoTriggerResponse a = new TodoTriggerResponse(
                    "id", "pattern", "proj", "AI", "MEDIUM", "wf", "WF Name",
                    ctx, true, 5, false, true, "desc", now, 3L, now
            );
            TodoTriggerResponse b = new TodoTriggerResponse(
                    "id", "pattern", "proj", "AI", "MEDIUM", "wf", "WF Name",
                    ctx, true, 5, false, true, "desc", now, 3L, now
            );

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_not_be_equal_when_field_differs() {
            Instant now = Instant.now();
            TodoTriggerResponse a = new TodoTriggerResponse(
                    "id-1", "p", "proj", "AI", "LOW", "wf", "Name",
                    Map.of(), true, 1, false, false, "d", now, 0L, now
            );
            TodoTriggerResponse b = new TodoTriggerResponse(
                    "id-2", "p", "proj", "AI", "LOW", "wf", "Name",
                    Map.of(), true, 1, false, false, "d", now, 0L, now
            );

            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    class ToStringMethod {

        @Test
        void should_include_key_field_values() {
            Instant now = Instant.parse("2026-04-25T12:00:00Z");
            TodoTriggerResponse response = new TodoTriggerResponse(
                    "trigger-xyz", "feature/*", "proj-1", "AI", "HIGH",
                    "wf-abc", "My Workflow", Map.of("foo", "bar"),
                    true, 10, true, false, "My description", now, 42L, now
            );

            String str = response.toString();

            assertThat(str).contains("trigger-xyz");
            assertThat(str).contains("feature/*");
            assertThat(str).contains("wf-abc");
            assertThat(str).contains("My Workflow");
            assertThat(str).contains("42");
        }
    }
}
