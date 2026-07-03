package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRollupTest {

    private static ProjectRollup sample() {
        return new ProjectRollup(
                "project-42",
                "Alpha",
                5L,
                2L,
                10L,
                3L,
                1L,
                4L,
                6L,
                7L,
                8L
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            ProjectRollup rollup = sample();

            assertThat(rollup.projectId()).isEqualTo("project-42");
            assertThat(rollup.projectName()).isEqualTo("Alpha");
            assertThat(rollup.open()).isEqualTo(5L);
            assertThat(rollup.inProgress()).isEqualTo(2L);
            assertThat(rollup.done()).isEqualTo(10L);
            assertThat(rollup.backlog()).isEqualTo(3L);
            assertThat(rollup.archived()).isEqualTo(1L);
            assertThat(rollup.staleCount()).isEqualTo(4L);
            assertThat(rollup.userActive()).isEqualTo(6L);
            assertThat(rollup.machineActive()).isEqualTo(7L);
            assertThat(rollup.needsYouCount()).isEqualTo(8L);
        }

        @Test
        void should_allow_null_identifiers_when_project_fields_are_absent() {
            ProjectRollup rollup = new ProjectRollup(
                    null, null, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

            assertThat(rollup.projectId()).isNull();
            assertThat(rollup.projectName()).isNull();
        }

        @Test
        void should_expose_zero_counts_when_constructed_with_all_zeros() {
            ProjectRollup rollup = new ProjectRollup(
                    "p", "n", 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

            assertThat(rollup.open()).isZero();
            assertThat(rollup.inProgress()).isZero();
            assertThat(rollup.done()).isZero();
            assertThat(rollup.backlog()).isZero();
            assertThat(rollup.archived()).isZero();
            assertThat(rollup.staleCount()).isZero();
            assertThat(rollup.userActive()).isZero();
            assertThat(rollup.machineActive()).isZero();
            assertThat(rollup.needsYouCount()).isZero();
        }

        @Test
        void should_preserve_extreme_count_values_when_constructed_with_long_bounds() {
            ProjectRollup rollup = new ProjectRollup(
                    "p", "n",
                    Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
                    Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE,
                    Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);

            assertThat(rollup.open()).isEqualTo(Long.MAX_VALUE);
            assertThat(rollup.inProgress()).isEqualTo(Long.MIN_VALUE);
            assertThat(rollup.done()).isEqualTo(Long.MAX_VALUE);
            assertThat(rollup.backlog()).isEqualTo(Long.MIN_VALUE);
            assertThat(rollup.archived()).isEqualTo(Long.MAX_VALUE);
            assertThat(rollup.staleCount()).isEqualTo(Long.MIN_VALUE);
            assertThat(rollup.userActive()).isEqualTo(Long.MAX_VALUE);
            assertThat(rollup.machineActive()).isEqualTo(Long.MIN_VALUE);
            assertThat(rollup.needsYouCount()).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    class EqualsHashCodeToString {

        @Test
        void should_be_equal_when_all_components_match() {
            ProjectRollup a = sample();
            ProjectRollup b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_not_be_equal_when_any_component_differs() {
            ProjectRollup base = sample();
            ProjectRollup differentId = new ProjectRollup(
                    "other", "Alpha", 5L, 2L, 10L, 3L, 1L, 4L, 6L, 7L, 8L);
            ProjectRollup differentName = new ProjectRollup(
                    "project-42", "Beta", 5L, 2L, 10L, 3L, 1L, 4L, 6L, 7L, 8L);
            ProjectRollup differentCount = new ProjectRollup(
                    "project-42", "Alpha", 99L, 2L, 10L, 3L, 1L, 4L, 6L, 7L, 8L);

            assertThat(base).isNotEqualTo(differentId);
            assertThat(base).isNotEqualTo(differentName);
            assertThat(base).isNotEqualTo(differentCount);
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("project-42");
        }

        @Test
        void should_contain_class_name_and_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text).contains("ProjectRollup");
            assertThat(text).contains("project-42");
            assertThat(text).contains("Alpha");
            assertThat(text).contains("5");
            assertThat(text).contains("8");
        }
    }
}
