package com.automation.dto.response;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSessionSummaryTest {

    private static AssistantSessionSummary sample() {
        return new AssistantSessionSummary(
                "session-123",
                "Hello, how can I help?",
                "/dashboard",
                7,
                Instant.parse("2026-04-25T10:15:30Z")
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_when_constructed_with_full_values() {
            AssistantSessionSummary summary = sample();

            assertThat(summary.id()).isEqualTo("session-123");
            assertThat(summary.preview()).isEqualTo("Hello, how can I help?");
            assertThat(summary.page()).isEqualTo("/dashboard");
            assertThat(summary.messageCount()).isEqualTo(7);
            assertThat(summary.startedAt()).isEqualTo(Instant.parse("2026-04-25T10:15:30Z"));
        }

        @Test
        void should_allow_null_string_fields_and_zero_message_count() {
            AssistantSessionSummary summary = new AssistantSessionSummary(null, null, null, 0, null);

            assertThat(summary.id()).isNull();
            assertThat(summary.preview()).isNull();
            assertThat(summary.page()).isNull();
            assertThat(summary.messageCount()).isZero();
            assertThat(summary.startedAt()).isNull();
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
        void should_not_be_equal_when_a_field_differs() {
            AssistantSessionSummary other = new AssistantSessionSummary(
                    "session-999",
                    "Hello, how can I help?",
                    "/dashboard",
                    7,
                    Instant.parse("2026-04-25T10:15:30Z")
            );

            assertThat(sample()).isNotEqualTo(other);
        }

        @Test
        void should_include_field_values_in_toString() {
            String text = sample().toString();

            assertThat(text)
                    .contains("session-123")
                    .contains("/dashboard")
                    .contains("7");
        }
    }
}
