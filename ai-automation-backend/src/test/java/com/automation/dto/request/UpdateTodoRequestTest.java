package com.automation.dto.request;

import com.automation.entity.Todo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateTodoRequestTest {

    private UpdateTodoRequest sample() {
        return new UpdateTodoRequest(
                "Updated title",
                "Updated description",
                Todo.Status.IN_PROGRESS,
                Todo.Priority.HIGH,
                List.of("backend", "testing"),
                "project-42",
                "workflow-7",
                "WORKFLOW_RUN",
                "run-123",
                "2026-12-31"
        );
    }

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_via_accessors() {
            UpdateTodoRequest request = sample();

            assertThat(request.title()).isEqualTo("Updated title");
            assertThat(request.description()).isEqualTo("Updated description");
            assertThat(request.status()).isEqualTo(Todo.Status.IN_PROGRESS);
            assertThat(request.priority()).isEqualTo(Todo.Priority.HIGH);
            assertThat(request.tags()).containsExactly("backend", "testing");
            assertThat(request.projectId()).isEqualTo("project-42");
            assertThat(request.linkedWorkflowId()).isEqualTo("workflow-7");
            assertThat(request.referenceType()).isEqualTo("WORKFLOW_RUN");
            assertThat(request.referenceId()).isEqualTo("run-123");
            assertThat(request.dueDate()).isEqualTo("2026-12-31");
        }

        @Test
        void should_allow_null_optional_fields() {
            UpdateTodoRequest request = new UpdateTodoRequest(
                    null, null, null, null, null, null, null, null, null, null);

            assertThat(request.title()).isNull();
            assertThat(request.description()).isNull();
            assertThat(request.status()).isNull();
            assertThat(request.priority()).isNull();
            assertThat(request.tags()).isNull();
            assertThat(request.projectId()).isNull();
            assertThat(request.linkedWorkflowId()).isNull();
            assertThat(request.referenceType()).isNull();
            assertThat(request.referenceId()).isNull();
            assertThat(request.dueDate()).isNull();
        }

        @Test
        void should_accept_empty_tags_list() {
            UpdateTodoRequest request = new UpdateTodoRequest(
                    "t", "d", Todo.Status.OPEN, Todo.Priority.LOW,
                    List.of(), "p", "w", "r", "i", "2026-01-01");

            assertThat(request.tags()).isEmpty();
        }

        @Test
        void should_support_all_status_values() {
            for (Todo.Status status : Todo.Status.values()) {
                UpdateTodoRequest request = new UpdateTodoRequest(
                        "t", "d", status, Todo.Priority.MEDIUM,
                        List.of(), "p", "w", "r", "i", "d");
                assertThat(request.status()).isEqualTo(status);
            }
        }

        @Test
        void should_support_all_priority_values() {
            for (Todo.Priority priority : Todo.Priority.values()) {
                UpdateTodoRequest request = new UpdateTodoRequest(
                        "t", "d", Todo.Status.OPEN, priority,
                        List.of(), "p", "w", "r", "i", "d");
                assertThat(request.priority()).isEqualTo(priority);
            }
        }
    }

    @Nested
    @DisplayName("Record equality and hashCode")
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            UpdateTodoRequest a = sample();
            UpdateTodoRequest b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_title_differs() {
            UpdateTodoRequest a = sample();
            UpdateTodoRequest b = new UpdateTodoRequest(
                    "different", "Updated description",
                    Todo.Status.IN_PROGRESS, Todo.Priority.HIGH,
                    List.of("backend", "testing"), "project-42",
                    "workflow-7", "WORKFLOW_RUN", "run-123", "2026-12-31");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_all_fields_in_toString() {
            String s = sample().toString();

            assertThat(s).contains("Updated title");
            assertThat(s).contains("Updated description");
            assertThat(s).contains("IN_PROGRESS");
            assertThat(s).contains("HIGH");
            assertThat(s).contains("project-42");
            assertThat(s).contains("workflow-7");
            assertThat(s).contains("WORKFLOW_RUN");
            assertThat(s).contains("run-123");
            assertThat(s).contains("2026-12-31");
        }
    }
}
