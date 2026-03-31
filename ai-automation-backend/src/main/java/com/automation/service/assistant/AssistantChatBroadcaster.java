package com.automation.service.assistant;

import com.automation.dto.AssistantPushEvent;
import com.automation.dto.AssistantStatusUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Broadcasts assistant chat status updates and push events via WebSocket.
 * Status updates go to /topic/assistant/{requestId}/status (per-request).
 * Push events go to /topic/assistant/{sessionId}/events (per-session).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantChatBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastStatus(String requestId, String phase, String detail, long startNanos) {
        if (requestId == null) return;
        try {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            messagingTemplate.convertAndSend(
                    "/topic/assistant/" + requestId + "/status",
                    new AssistantStatusUpdate("assistant_status", requestId, phase, detail, elapsedMs, Instant.now().toString())
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast assistant status: {}", e.getMessage());
        }
    }

    public void broadcastEvent(String sessionId, String eventKind, String title, String detail, String linkTo) {
        if (sessionId == null) return;
        try {
            messagingTemplate.convertAndSend(
                    "/topic/assistant/" + sessionId + "/events",
                    new AssistantPushEvent("assistant_event", sessionId, eventKind, title, detail, linkTo, Instant.now().toString())
            );
        } catch (Exception e) {
            log.warn("Failed to broadcast assistant event: {}", e.getMessage());
        }
    }
}
