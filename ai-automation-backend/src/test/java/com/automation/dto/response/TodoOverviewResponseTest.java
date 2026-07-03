package com.automation.dto.response;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TodoOverviewResponseTest {

    private static ProjectRollup rollup() {
        return new ProjectRollup(
                "proj-1", "AutoPilot",
                5L, 2L, 10L, 1L, 3L, 4L, 6L, 1L, 2L
        );
    }

    private static NeedsYouItem needsYouItem() {
        return new NeedsYouItem(
                "todo-1", "Fix bug", "proj-1", "AutoPilot",
                Todo.Priority.HIGH,
                Instant.parse("2026-07-10T00:00:00Z"),
                Todo.Source.USER,
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-20T00:00:00Z"),
                32L, false
        );
    }

    private static TodoOverviewResponse sample() {
        return new TodoOverviewResponse(
                List.of(rollup()),
                List.of(needsYouItem()),
                Instant.parse("2026-07-03T12:00:00Z"),
                14
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            TodoOverviewResponse response = sample();

            assertThat(response.projects()).containsExactly(rollup());
            assertThat(response.needsYou()).containsExactly(needsYouItem());
            assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-07-03T12:00:00Z"));
            assertThat(response.staleThresholdDays()).isEqualTo(14);
        }

        @Test
        void should_allow_null_fields_when_constructed_without_values() {
            TodoOverviewResponse response = new TodoOverviewResponse(null, null, null, 0);

            assertThat(response.projects()).isNull();
            assertThat(response.needsYou()).isNull();
            assertThat(response.generatedAt()).isNull();
            assertThat(response.staleThresholdDays()).isZero();
        }

        @Test
        void should_accept_empty_lists_when_no_projects_or_needs_you_items() {
            TodoOverviewResponse response = new TodoOverviewResponse(
                    List.of(), List.of(), Instant.parse("2026-07-03T12:00:00Z"), 0);

            assertThat(response.projects()).isEmpty();
            assertThat(response.needsYou()).isEmpty();
            assertThat(response.staleThresholdDays()).isZero();
        }

        @Test
        void should_carry_zero_counts_when_project_rollup_is_all_zero() {
            ProjectRollup empty = new ProjectRollup(
                    "proj-0", "Empty", 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
            TodoOverviewResponse response = new TodoOverviewResponse(
                    List.of(empty), List.of(), Instant.parse("2026-07-03T12:00:00Z"), 7);

            assertThat(response.projects()).hasSize(1);
            assertThat(response.projects().get(0).open()).isZero();
            assertThat(response.projects().get(0).inProgress()).isZero();
            assertThat(response.projects().get(0).done()).isZero();
            assertThat(response.projects().get(0).needsYouCount()).isZero();
        }
    }

    @Nested
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            TodoOverviewResponse a = sample();
            TodoOverviewResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_a_field_differs() {
            TodoOverviewResponse a = sample();
            TodoOverviewResponse b = new TodoOverviewResponse(
                    a.projects(), a.needsYou(), a.generatedAt(), 30);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_include_all_fields_in_toString() {
            String s = sample().toString();

            assertThat(s).contains("proj-1")
                    .contains("AutoPilot")
                    .contains("todo-1")
                    .contains("Fix bug")
                    .contains("2026-07-03T12:00:00Z")
                    .contains("14");
        }
    }
}
