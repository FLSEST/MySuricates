package com.automation.dto.projection;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NeedsYouRowTest {

    private static NeedsYouRow sample() {
        return new NeedsYouRow(
                "todo-1",
                "Fix login bug",
                "project-42",
                Todo.Priority.HIGH,
                Instant.parse("2026-07-10T00:00:00Z"),
                Todo.Source.USER,
                Instant.parse("2026-07-01T08:00:00Z"),
                Instant.parse("2026-07-02T09:30:00Z")
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            NeedsYouRow row = sample();

            assertThat(row.id()).isEqualTo("todo-1");
            assertThat(row.title()).isEqualTo("Fix login bug");
            assertThat(row.projectId()).isEqualTo("project-42");
            assertThat(row.priority()).isEqualTo(Todo.Priority.HIGH);
            assertThat(row.dueDate()).isEqualTo(Instant.parse("2026-07-10T00:00:00Z"));
            assertThat(row.source()).isEqualTo(Todo.Source.USER);
            assertThat(row.createdAt()).isEqualTo(Instant.parse("2026-07-01T08:00:00Z"));
            assertThat(row.updatedAt()).isEqualTo(Instant.parse("2026-07-02T09:30:00Z"));
        }

        @Test
        void should_allow_null_values_when_optional_fields_are_absent() {
            NeedsYouRow row = new NeedsYouRow(
                    null, null, null, null, null, null, null, null
            );

            assertThat(row.id()).isNull();
            assertThat(row.title()).isNull();
            assertThat(row.projectId()).isNull();
            assertThat(row.priority()).isNull();
            assertThat(row.dueDate()).isNull();
            assertThat(row.source()).isNull();
            assertThat(row.createdAt()).isNull();
            assertThat(row.updatedAt()).isNull();
        }

        @Test
        void should_support_all_priority_values() {
            for (Todo.Priority p : Todo.Priority.values()) {
                NeedsYouRow row = new NeedsYouRow(
                        "id", "t", "pid", p, null, Todo.Source.AI, null, null
                );
                assertThat(row.priority()).isEqualTo(p);
            }
        }

        @Test
        void should_support_all_source_values() {
            for (Todo.Source s : Todo.Source.values()) {
                NeedsYouRow row = new NeedsYouRow(
                        "id", "t", "pid", Todo.Priority.LOW, null, s, null, null
                );
                assertThat(row.source()).isEqualTo(s);
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
            NeedsYouRow base = sample();
            NeedsYouRow other = new NeedsYouRow(
                    "todo-999", base.title(), base.projectId(), base.priority(),
                    base.dueDate(), base.source(), base.createdAt(), base.updatedAt()
            );

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_not_be_equal_when_priority_differs() {
            NeedsYouRow base = sample();
            NeedsYouRow other = new NeedsYouRow(
                    base.id(), base.title(), base.projectId(), Todo.Priority.LOW,
                    base.dueDate(), base.source(), base.createdAt(), base.updatedAt()
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
