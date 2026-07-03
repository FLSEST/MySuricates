package com.automation.dto.response;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NeedsYouItemTest {

    private static NeedsYouItem sample() {
        return new NeedsYouItem(
                "todo-1",
                "Fix login bug",
                "project-42",
                "MyApp",
                Todo.Priority.HIGH,
                Instant.parse("2026-07-10T00:00:00Z"),
                Todo.Source.USER,
                Instant.parse("2026-07-01T08:00:00Z"),
                Instant.parse("2026-07-02T09:30:00Z"),
                2L,
                false
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            NeedsYouItem item = sample();

            assertThat(item.id()).isEqualTo("todo-1");
            assertThat(item.title()).isEqualTo("Fix login bug");
            assertThat(item.projectId()).isEqualTo("project-42");
            assertThat(item.projectName()).isEqualTo("MyApp");
            assertThat(item.priority()).isEqualTo(Todo.Priority.HIGH);
            assertThat(item.dueDate()).isEqualTo(Instant.parse("2026-07-10T00:00:00Z"));
            assertThat(item.source()).isEqualTo(Todo.Source.USER);
            assertThat(item.createdAt()).isEqualTo(Instant.parse("2026-07-01T08:00:00Z"));
            assertThat(item.updatedAt()).isEqualTo(Instant.parse("2026-07-02T09:30:00Z"));
            assertThat(item.ageDays()).isEqualTo(2L);
            assertThat(item.overdue()).isFalse();
        }

        @Test
        void should_allow_null_optional_fields() {
            NeedsYouItem item = new NeedsYouItem(
                    null, null, null, null,
                    Todo.Priority.LOW,
                    null,
                    Todo.Source.AI,
                    null, null,
                    0L, false
            );

            assertThat(item.id()).isNull();
            assertThat(item.title()).isNull();
            assertThat(item.projectId()).isNull();
            assertThat(item.projectName()).isNull();
            assertThat(item.dueDate()).isNull();
            assertThat(item.createdAt()).isNull();
            assertThat(item.updatedAt()).isNull();
            assertThat(item.ageDays()).isZero();
            assertThat(item.overdue()).isFalse();
        }

        @Test
        void should_reflect_overdue_true() {
            NeedsYouItem item = new NeedsYouItem(
                    "todo-2", "Overdue task", "p-1", "Proj",
                    Todo.Priority.CRITICAL,
                    Instant.parse("2026-06-01T00:00:00Z"),
                    Todo.Source.WORKFLOW,
                    Instant.parse("2026-05-01T00:00:00Z"),
                    Instant.parse("2026-05-15T00:00:00Z"),
                    30L, true
            );

            assertThat(item.overdue()).isTrue();
            assertThat(item.ageDays()).isEqualTo(30L);
            assertThat(item.priority()).isEqualTo(Todo.Priority.CRITICAL);
            assertThat(item.source()).isEqualTo(Todo.Source.WORKFLOW);
        }

        @Test
        void should_support_all_priority_values() {
            for (Todo.Priority p : Todo.Priority.values()) {
                NeedsYouItem item = new NeedsYouItem(
                        "id", "t", "pid", "pname",
                        p, null, Todo.Source.AI,
                        null, null, 0L, false
                );
                assertThat(item.priority()).isEqualTo(p);
            }
        }

        @Test
        void should_support_all_source_values() {
            for (Todo.Source s : Todo.Source.values()) {
                NeedsYouItem item = new NeedsYouItem(
                        "id", "t", "pid", "pname",
                        Todo.Priority.LOW, null, s,
                        null, null, 0L, false
                );
                assertThat(item.source()).isEqualTo(s);
            }
        }
    }

    @Nested
    class RecordSemantics {

        @Test
        void should_be_equal_when_all_fields_match() {
            assertThat(sample()).isEqualTo(sample());
        }

        @Test
        void should_have_consistent_hashCode_for_equal_records() {
            assertThat(sample().hashCode()).isEqualTo(sample().hashCode());
        }

        @Test
        void should_not_be_equal_when_id_differs() {
            NeedsYouItem other = new NeedsYouItem(
                    "todo-999",
                    "Fix login bug",
                    "project-42",
                    "MyApp",
                    Todo.Priority.HIGH,
                    Instant.parse("2026-07-10T00:00:00Z"),
                    Todo.Source.USER,
                    Instant.parse("2026-07-01T08:00:00Z"),
                    Instant.parse("2026-07-02T09:30:00Z"),
                    2L,
                    false
            );

            assertThat(sample()).isNotEqualTo(other);
        }

        @Test
        void should_not_be_equal_when_overdue_differs() {
            NeedsYouItem base = sample();
            NeedsYouItem other = new NeedsYouItem(
                    base.id(), base.title(), base.projectId(), base.projectName(),
                    base.priority(), base.dueDate(), base.source(),
                    base.createdAt(), base.updatedAt(), base.ageDays(),
                    !base.overdue()
            );

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_include_key_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("todo-1")
                    .contains("Fix login bug")
                    .contains("project-42")
                    .contains("HIGH");
        }
    }
}
