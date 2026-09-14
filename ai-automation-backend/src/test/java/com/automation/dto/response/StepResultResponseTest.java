package com.automation.dto.response;

import com.automation.entity.StepStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepResultResponseTest {

    private StepResultResponse sample() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("retries", 2);
        metadata.put("provider", "anthropic");
        return new StepResultResponse(
                "step-123",
                0,
                "input-data",
                "output-data",
                StepStatus.COMPLETED,
                Instant.parse("2025-01-01T10:00:00Z"),
                Instant.parse("2025-01-01T10:00:05Z"),
                5000L,
                metadata,
                "shell",
                "Build"
        );
    }

    @Nested
    @DisplayName("Constructor and accessors")
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_via_accessors() {
            StepResultResponse response = sample();

            assertThat(response.id()).isEqualTo("step-123");
            assertThat(response.stepIndex()).isZero();
            assertThat(response.input()).isEqualTo("input-data");
            assertThat(response.output()).isEqualTo("output-data");
            assertThat(response.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(response.startedAt()).isEqualTo(Instant.parse("2025-01-01T10:00:00Z"));
            assertThat(response.completedAt()).isEqualTo(Instant.parse("2025-01-01T10:00:05Z"));
            assertThat(response.durationMs()).isEqualTo(5000L);
            assertThat(response.metadata())
                    .containsEntry("retries", 2)
                    .containsEntry("provider", "anthropic");
            assertThat(response.blockType()).isEqualTo("shell");
            assertThat(response.blockName()).isEqualTo("Build");
        }

        @Test
        void should_allow_null_optional_fields() {
            StepResultResponse response = new StepResultResponse(
                    null, 0, null, null, null, null, null, null, null, null, null);

            assertThat(response.id()).isNull();
            assertThat(response.input()).isNull();
            assertThat(response.output()).isNull();
            assertThat(response.status()).isNull();
            assertThat(response.startedAt()).isNull();
            assertThat(response.completedAt()).isNull();
            assertThat(response.durationMs()).isNull();
            assertThat(response.metadata()).isNull();
            assertThat(response.blockType()).isNull();
            assertThat(response.blockName()).isNull();
        }

        @Test
        void should_handle_large_duration_values() {
            StepResultResponse response = new StepResultResponse(
                    "x", 999, "i", "o", StepStatus.COMPLETED,
                    Instant.EPOCH, Instant.EPOCH, Long.MAX_VALUE, Map.of(), null, null);

            assertThat(response.durationMs()).isEqualTo(Long.MAX_VALUE);
            assertThat(response.stepIndex()).isEqualTo(999);
        }

        @Test
        void should_preserve_step_index_zero() {
            StepResultResponse response = sample();
            assertThat(response.stepIndex()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Record equality and hashCode")
    class RecordEquality {

        @Test
        void should_be_equal_when_all_fields_match() {
            StepResultResponse a = sample();
            StepResultResponse b = sample();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void should_differ_when_id_differs() {
            StepResultResponse a = sample();
            StepResultResponse b = new StepResultResponse(
                    "other", a.stepIndex(), a.input(), a.output(), a.status(),
                    a.startedAt(), a.completedAt(), a.durationMs(), a.metadata(), a.blockType(), a.blockName());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_differ_when_status_differs() {
            StepResultResponse a = sample();
            StepStatus other = null;
            for (StepStatus s : StepStatus.values()) {
                if (s != a.status()) { other = s; break; }
            }
            if (other == null) return; // Enum hat nur einen Wert
            StepResultResponse b = new StepResultResponse(
                    a.id(), a.stepIndex(), a.input(), a.output(), other,
                    a.startedAt(), a.completedAt(), a.durationMs(), a.metadata(), a.blockType(), a.blockName());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void should_not_equal_other_type() {
            assertThat(sample()).isNotEqualTo("not a response");
            assertThat(sample()).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        void should_include_field_values() {
            String str = sample().toString();

            assertThat(str)
                    .contains("step-123")
                    .contains("input-data")
                    .contains("output-data")
                    .contains("5000");
        }
    }
}
