package com.automation.dto.projection;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCountTest {

    private static ProjectCount sample() {
        return new ProjectCount("project-42", 7L);
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            ProjectCount count = sample();

            assertThat(count.projectId()).isEqualTo("project-42");
            assertThat(count.cnt()).isEqualTo(7L);
        }

        @Test
        void should_allow_null_projectId_when_not_provided() {
            ProjectCount count = new ProjectCount(null, 1L);

            assertThat(count.projectId()).isNull();
            assertThat(count.cnt()).isEqualTo(1L);
        }

        @Test
        void should_hold_zero_when_project_has_no_matches() {
            ProjectCount count = new ProjectCount("empty-project", 0L);

            assertThat(count.cnt()).isZero();
        }

        @Test
        void should_hold_max_value_when_count_is_long_max() {
            ProjectCount count = new ProjectCount("big-project", Long.MAX_VALUE);

            assertThat(count.cnt()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void should_hold_large_values_when_count_exceeds_int_range() {
            long big = Integer.MAX_VALUE + 1L;
            ProjectCount count = new ProjectCount("big-project", big);

            assertThat(count.cnt()).isEqualTo(big);
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
            ProjectCount base = sample();
            ProjectCount other = new ProjectCount("project-999", base.cnt());

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_not_be_equal_when_cnt_differs() {
            ProjectCount base = sample();
            ProjectCount other = new ProjectCount(base.projectId(), base.cnt() + 1);

            assertThat(base).isNotEqualTo(other);
        }

        @Test
        void should_include_key_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("project-42")
                    .contains("cnt=7");
        }
    }
}
