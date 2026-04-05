package com.automation.service.assistant;

import com.automation.dto.AssistantPushEvent;
import com.automation.dto.AssistantStatusUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssistantChatBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private AssistantChatBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new AssistantChatBroadcaster(messagingTemplate);
    }

    // --- broadcastStatus ---

    @Test
    void broadcastStatus_sendsToCorrectTopic() {
        long startNanos = System.nanoTime() - 500_000_000L; // 500ms ago

        broadcaster.broadcastStatus("req-1", "thinking", "Processing query", startNanos);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AssistantStatusUpdate> payloadCaptor = ArgumentCaptor.forClass(AssistantStatusUpdate.class);
        verify(messagingTemplate).convertAndSend(topicCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("/topic/assistant/req-1/status");

        AssistantStatusUpdate update = payloadCaptor.getValue();
        assertThat(update.type()).isEqualTo("assistant_status");
        assertThat(update.requestId()).isEqualTo("req-1");
        assertThat(update.phase()).isEqualTo("thinking");
        assertThat(update.detail()).isEqualTo("Processing query");
        assertThat(update.elapsedMs()).isGreaterThanOrEqualTo(400); // at least ~500ms elapsed
        assertThat(update.timestamp()).isNotBlank();
    }

    @Test
    void broadcastStatus_nullRequestId_doesNotSend() {
        broadcaster.broadcastStatus(null, "thinking", "detail", System.nanoTime());

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void broadcastStatus_messagingFailure_doesNotThrow() {
        doThrow(new RuntimeException("WebSocket down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(AssistantStatusUpdate.class));

        // Should not throw
        broadcaster.broadcastStatus("req-1", "phase", "detail", System.nanoTime());
    }

    // --- broadcastEvent ---

    @Test
    void broadcastEvent_sendsToCorrectTopic() {
        broadcaster.broadcastEvent("sess-1", "workflow_started", "WF Running", "Step 1", "/runs/123");

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AssistantPushEvent> payloadCaptor = ArgumentCaptor.forClass(AssistantPushEvent.class);
        verify(messagingTemplate).convertAndSend(topicCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("/topic/assistant/sess-1/events");

        AssistantPushEvent event = payloadCaptor.getValue();
        assertThat(event.type()).isEqualTo("assistant_event");
        assertThat(event.sessionId()).isEqualTo("sess-1");
        assertThat(event.eventKind()).isEqualTo("workflow_started");
        assertThat(event.title()).isEqualTo("WF Running");
        assertThat(event.detail()).isEqualTo("Step 1");
        assertThat(event.linkTo()).isEqualTo("/runs/123");
        assertThat(event.timestamp()).isNotBlank();
    }

    @Test
    void broadcastEvent_nullSessionId_doesNotSend() {
        broadcaster.broadcastEvent(null, "kind", "title", "detail", "/link");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void broadcastEvent_messagingFailure_doesNotThrow() {
        doThrow(new RuntimeException("WebSocket down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(AssistantPushEvent.class));

        // Should not throw
        broadcaster.broadcastEvent("sess-1", "kind", "title", "detail", "/link");
    }
}
