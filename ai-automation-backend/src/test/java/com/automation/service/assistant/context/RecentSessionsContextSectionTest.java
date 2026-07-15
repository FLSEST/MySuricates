package com.automation.service.assistant.context;

import com.automation.entity.AssistantSession;
import com.automation.repository.AssistantSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecentSessionsContextSectionTest {

    @Mock private AssistantSessionRepository assistantSessionRepository;

    private RecentSessionsContextSection section;

    @BeforeEach
    void setUp() {
        section = new RecentSessionsContextSection(assistantSessionRepository, new ObjectMapper());
    }

    private AssistantSession buildSession(String messagesJson) {
        return AssistantSession.builder()
                .id("session-1")
                .page("dashboard")
                .startedAt(Instant.parse("2026-03-20T10:15:00Z"))
                .messages(messagesJson)
                .build();
    }

    @Test
    void noSessions_appendsNothing() {
        when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void sessionWithExchange_rendersUserAndResponse() {
        when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of(buildSession(
                "[{\"role\":\"user\",\"content\":\"Wie ist der Status?\"},"
                        + "{\"role\":\"assistant\",\"content\":\"Alles gruen.\"}]")));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("## Recent Conversations (Context)")
                .contains("User: \"Wie ist der Status?\"")
                .contains("→ Response: \"Alles gruen.\"");
    }

    @Test
    void sessionWithoutUserMessage_isSkipped() {
        when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc()).thenReturn(List.of(buildSession(
                "[{\"role\":\"assistant\",\"content\":\"Hallo\"}]")));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).doesNotContain("User:");
    }

    @Test
    void invalidMessagesJson_isSkipped() {
        when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc())
                .thenReturn(List.of(buildSession("not-json")));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).doesNotContain("User:");
    }

    @Test
    void exception_doesNotThrow() {
        when(assistantSessionRepository.findTop5ByOrderByStartedAtDesc())
                .thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}
