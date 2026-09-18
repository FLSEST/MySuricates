package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportToPoolResponseTest {

    private static ExportToPoolResponse sample() {
        return new ExportToPoolResponse("pool-123", "workflow-export.json", List.of("blocks[0].config.workflowId"));
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            ExportToPoolResponse response = sample();

            assertThat(response.poolId()).isEqualTo("pool-123");
            assertThat(response.filename()).isEqualTo("workflow-export.json");
            assertThat(response.sanitizedPaths()).containsExactly("blocks[0].config.workflowId");
        }

        @Test
        void should_allow_null_fields() {
            ExportToPoolResponse response = new ExportToPoolResponse(null, null, List.of());

            assertThat(response.poolId()).isNull();
            assertThat(response.filename()).isNull();
            assertThat(response.sanitizedPaths()).isEmpty();
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
        void should_not_be_equal_when_poolId_differs() {
            ExportToPoolResponse other = new ExportToPoolResponse("pool-999", "workflow-export.json", List.of());

            assertThat(sample()).isNotEqualTo(other);
        }

        @Test
        void should_not_be_equal_when_filename_differs() {
            ExportToPoolResponse other = new ExportToPoolResponse("pool-123", "other.json", List.of());

            assertThat(sample()).isNotEqualTo(other);
        }

        @Test
        void should_include_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("pool-123")
                    .contains("workflow-export.json");
        }
    }
}
