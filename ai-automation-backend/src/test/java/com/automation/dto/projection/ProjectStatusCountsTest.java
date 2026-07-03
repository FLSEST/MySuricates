package com.automation.dto.projection;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectStatusCountsTest {

    private static ProjectStatusCounts sample() {
        return new ProjectStatusCounts("project-42", 3L, 2L, 10L, 1L, 4L, 5L, 6L);
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            ProjectStatusCounts counts = sample();

            assertThat(counts.projectId()).isEqualTo("project-42");
            assertThat(counts.open()).isEqualTo(3L);
            assertThat(counts.inProgress()).isEqualTo(2L);
            assertThat(counts.done()).isEqualTo(10L);
            assertThat(counts.backlog()).isEqualTo(1L);
            assertThat(counts.archived()).isEqualTo(4L);
            assertThat(counts.userActive()).isEqualTo(5L);
            assertThat(counts.machineActive()).isEqualTo(6L);
        }

        @Test
        void should_allow_null_projectId_when_not_provided() {
            ProjectStatusCounts counts = new ProjectStatusCounts(null, 1L, 1L, 1L, 1L, 1L, 1L, 1L);

            assertThat(counts.projectId()).isNull();
        }

        @Test
        void should_hold_zero_for_all_counts_when_project_has_no_todos() {
            ProjectStatusCounts counts = new ProjectStatusCounts("empty-project", 0L, 0L, 0L, 0L, 0L, 0L, 0L);

            assertThat(counts.open()).isZero();
            assertThat(counts.inProgress()).isZero();
            assertThat(counts.done()).isZero();
            assertThat(counts.backlog()).isZero();
            assertThat(counts.archived()).isZero();
            assertThat(counts.userActive()).isZero();
            assertThat(counts.machineActive()).isZero();
        }

        @Test
        void should_hold_large_values_when_counts_exceed_int_range() {
            long big = Integer.MAX_VALUE + 1L;
            ProjectStatusCounts counts = new ProjectStatusCounts("big-project", big, big, big, big, big, big, big);

            assertThat(counts.open()).isEqualTo(big);
            assertThat(counts.machineActive()).isEqualTo(big);
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
        void should_not_be_equal_when_projectId_differs() {
            ProjectStatusCounts base = sample();
            ProjectStatusCounts other = new ProjectStatusCounts(
                    "project-999", base.open(), base.inProgress(), base.done(),
                    base.backlog(), base.archived(), base.userActive(), base.machineActive()
            );

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_not_be_equal_when_a_count_differs() {
            ProjectStatusCounts base = sample();
            ProjectStatusCounts other = new ProjectStatusCounts(
                    base.projectId(), base.open() + 1, base.inProgress(), base.done(),
                    base.backlog(), base.archived(), base.userActive(), base.machineActive()
            );

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_include_key_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("project-42")
                    .contains("open=3")
                    .contains("machineActive=6");
        }
    }
}
