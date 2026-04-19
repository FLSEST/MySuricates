package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.dto.response.AssistantSessionDetail;
import com.automation.dto.response.AssistantSessionSummary;
import com.automation.entity.AssistantSession;
import com.automation.repository.AssistantSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantSessionServiceTest {

    @Mock private AssistantSessionRepository sessionRepository;

    private AssistantSessionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new AssistantSessionService(sessionRepository, objectMapper);
    }

    @Test
    void appendMessages_newSession_createsAndSavesWithMessages() {
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());

        var context = new AssistantChatRequest.PageContext("workflows", null, null);
        var req = new AssistantChatRequest("Hello", null, context, null, "sess-1", null);
        var res = new AssistantChatResponse("Hi there", null, null, "CLAUDE", 150L);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        AssistantSession saved = captor.getValue();
        assertThat(saved.getMessageCount()).isEqualTo(2);
        assertThat(saved.getMessages()).contains("Hello");
        assertThat(saved.getMessages()).contains("Hi there");
        assertThat(saved.getPage()).isEqualTo("workflows");
        assertThat(saved.getPreview()).isEqualTo("Hello");
    }

    @Test
    void appendMessages_existingSession_appendsMessages() {
        AssistantSession existing = AssistantSession.builder()
                .id("sess-1").page("dashboard").messages("[]").messageCount(0).build();
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(existing));

        var req = new AssistantChatRequest("Question", null, null, null, "sess-1", null);
        var res = new AssistantChatResponse("Answer", null, null, null);

        service.appendMessages("sess-1", req, res);

        verify(sessionRepository).save(existing);
        assertThat(existing.getMessageCount()).isEqualTo(2);
    }

    @Test
    void appendMessages_nullSessionId_doesNothing() {
        var req = new AssistantChatRequest("Hello", null, null, null, null, null);
        var res = new AssistantChatResponse("Hi", null);

        service.appendMessages(null, req, res);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void appendMessages_longAssistantMessage_storedInFull() {
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());

        var req = new AssistantChatRequest("Q", null, null, null, "sess-1", null);
        String longMsg = "A".repeat(1000);
        var res = new AssistantChatResponse(longMsg, null, null, null);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getMessages()).contains("A".repeat(1000));
    }

    @Test
    void appendMessages_corruptJson_startsWithEmptyList() {
        AssistantSession existing = AssistantSession.builder()
                .id("sess-1").page("dashboard").messages("{broken json").messageCount(0).build();
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.of(existing));

        var req = new AssistantChatRequest("Q", null, null, null, "sess-1", null);
        var res = new AssistantChatResponse("A", null);

        service.appendMessages("sess-1", req, res);

        verify(sessionRepository).save(existing);
        assertThat(existing.getMessageCount()).isEqualTo(2);
    }

    @Test
    void appendMessages_nullContext_defaultsToDashboard() {
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());

        var req = new AssistantChatRequest("Q", null, null, null, "sess-1", null);
        var res = new AssistantChatResponse("A", null);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPage()).isEqualTo("dashboard");
    }

    @Test
    void appendMessages_longPreview_truncatedTo100() {
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());

        String longMessage = "B".repeat(200);
        var req = new AssistantChatRequest(longMessage, null, null, null, "sess-1", null);
        var res = new AssistantChatResponse("A", null);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPreview()).hasSize(101); // 100 + "…"
        assertThat(captor.getValue().getPreview()).endsWith("…");
    }

    // ── listRecentSessions ─────────────────────────────────────────────────

    @Test
    void listRecentSessions_returnsSummaries() {
        var s1 = AssistantSession.builder()
                .id("s1").preview("Hello").page("dashboard").messageCount(4)
                .startedAt(Instant.parse("2026-04-14T10:00:00Z")).build();
        var s2 = AssistantSession.builder()
                .id("s2").preview("Help me").page("workflows").messageCount(2)
                .startedAt(Instant.parse("2026-04-14T09:00:00Z")).build();
        when(sessionRepository.findTop50ByOrderByStartedAtDesc()).thenReturn(List.of(s1, s2));

        List<AssistantSessionSummary> result = service.listRecentSessions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("s1");
        assertThat(result.get(0).preview()).isEqualTo("Hello");
        assertThat(result.get(0).messageCount()).isEqualTo(4);
        assertThat(result.get(1).id()).isEqualTo("s2");
    }

    // ── getSession ─────────────────────────────────────────────────────────

    @Test
    void getSession_existingId_returnsDetail() {
        var session = AssistantSession.builder()
                .id("s1").preview("Hi").page("dashboard").entityId(null)
                .messages("[{\"role\":\"user\",\"content\":\"Hi\",\"timestamp\":1000}]")
                .messageCount(1).startedAt(Instant.parse("2026-04-14T10:00:00Z")).build();
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        AssistantSessionDetail detail = service.getSession("s1");

        assertThat(detail.id()).isEqualTo("s1");
        assertThat(detail.messages()).hasSize(1);
        assertThat(detail.messages().get(0).get("content")).isEqualTo("Hi");
    }

    @Test
    void getSession_unknownId_throws() {
        when(sessionRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSession("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session not found");
    }

    // ── deleteSession ──────────────────────────────────────────────────────

    @Test
    void deleteSession_callsRepository() {
        service.deleteSession("s1");
        verify(sessionRepository).deleteById("s1");
    }
}
