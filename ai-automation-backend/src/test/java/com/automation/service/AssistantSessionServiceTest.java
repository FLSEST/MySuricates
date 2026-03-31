package com.automation.service;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.entity.AssistantSession;
import com.automation.repository.AssistantSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        var res = new AssistantChatResponse("Hi there", null, null, 150L);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        AssistantSession saved = captor.getValue();
        assertThat(saved.getMessageCount()).isEqualTo(2);
        assertThat(saved.getMessages()).contains("Hello");
        assertThat(saved.getMessages()).contains("Hi there");
        assertThat(saved.getPage()).isEqualTo("workflows");
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
    void appendMessages_longAssistantMessage_truncatedTo500() {
        when(sessionRepository.findById("sess-1")).thenReturn(Optional.empty());

        var req = new AssistantChatRequest("Q", null, null, null, "sess-1", null);
        String longMsg = "A".repeat(1000);
        var res = new AssistantChatResponse(longMsg, null, null, null);

        service.appendMessages("sess-1", req, res);

        ArgumentCaptor<AssistantSession> captor = ArgumentCaptor.forClass(AssistantSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getMessages()).doesNotContain("A".repeat(1000));
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
}
