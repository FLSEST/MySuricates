package com.automation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssistantSessionDetailTest {

    private static AssistantSessionDetail sample() {
        return new AssistantSessionDetail(
                "session-123",
                "Hello, how can I help?",
                "workflows",
                "wf-42",
                List.of(Map.of("role", "user", "content", "Hi")),
                1,
                Instant.parse("2026-04-25T10:00:00Z"),
                Instant.parse("2026-04-25T10:05:00Z")
        );
    }

    @Nested
    class ConstructorAndAccessors {

        @Test
        void should_expose_all_fields_via_accessors() {
            AssistantSessionDetail detail = sample();

            assertThat(detail.id()).isEqualTo("session-123");
            assertThat(detail.preview()).isEqualTo("Hello, how can I help?");
            assertThat(detail.page()).isEqualTo("workflows");
            assertThat(detail.entityId()).isEqualTo("wf-42");
            assertThat(detail.messages()).hasSize(1)
                    .first().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("role", "user")
                    .containsEntry("content", "Hi");
            assertThat(detail.messageCount()).isEqualTo(1);
            assertThat(detail.startedAt()).isEqualTo(Instant.parse("2026-04-25T10:00:00Z"));
            assertThat(detail.endedAt()).isEqualTo(Instant.parse("2026-04-25T10:05:00Z"));
        }

        @Test
        void should_allow_null_optional_fields() {
            AssistantSessionDetail detail = new AssistantSessionDetail(
                    "session-1", null, null, null, null, 0, null, null);

            assertThat(detail.id()).isEqualTo("session-1");
            assertThat(detail.preview()).isNull();
            assertThat(detail.page()).isNull();
            assertThat(detail.entityId()).isNull();
            assertThat(detail.messages()).isNull();
            assertThat(detail.messageCount()).isZero();
            assertThat(detail.startedAt()).isNull();
            assertThat(detail.endedAt()).isNull();
        }

        @Test
        void should_support_empty_messages_list() {
            AssistantSessionDetail detail = new AssistantSessionDetail(
                    "id", "p", "page", "e",
                    List.of(), 0,
                    Instant.parse("2026-04-25T10:00:00Z"), null);

            assertThat(detail.messages()).isEmpty();
            assertThat(detail.messageCount()).isZero();
            assertThat(detail.endedAt()).isNull();
        }

        @Test
        void should_support_multiple_messages() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hi"),
                    Map.of("role", "assistant", "content", "Hello!"),
                    Map.of("role", "user", "content", "Bye"));

            AssistantSessionDetail detail = new AssistantSessionDetail(
                    "id", "preview", "page", "entity",
                    messages, messages.size(),
                    Instant.parse("2026-04-25T10:00:00Z"),
                    Instant.parse("2026-04-25T10:10:00Z"));

            assertThat(detail.messages()).hasSize(3);
            assertThat(detail.messageCount()).isEqualTo(3);
        }
    }

    @Nested
    class RecordSemantics {

        @Test
        void should_be_equal_when_all_fields_match() {
            AssistantSessionDetail a = sample();
            AssistantSessionDetail b = sample();

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void should_not_be_equal_when_field_differs() {
            AssistantSessionDetail a = sample();
            AssistantSessionDetail b = new AssistantSessionDetail(
                    "different-id",
                    a.preview(), a.page(), a.entityId(),
                    a.messages(), a.messageCount(),
                    a.startedAt(), a.endedAt());

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void toString_should_contain_field_values() {
            AssistantSessionDetail detail = sample();

            assertThat(detail.toString())
                    .contains("session-123")
                    .contains("workflows")
                    .contains("wf-42");
        }
    }
}
