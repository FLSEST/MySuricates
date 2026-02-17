package com.automation.websocket;

import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class LogWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowRunRepository runRepository;

    // Track active subscribers per run
    private final Map<String, Set<String>> runSubscribers = new ConcurrentHashMap<>();

    @EventListener
    @Transactional(readOnly = true)
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();

        if (destination != null && destination.startsWith("/topic/executions/")) {
            String runId = extractRunId(destination);
            if (runId != null) {
                runSubscribers.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
                log.info("Client {} subscribed to execution logs: {}", sessionId, runId);

                try {
                    // Send current status immediately
                    runRepository.findById(runId).ifPresent(run -> {
                        messagingTemplate.convertAndSend(destination, Map.of(
                                "type", "status",
                                "runId", runId,
                                "status", run.getStatus(),
                                "stepsCompleted", run.getSteps().size()
                        ));
                    });
                } catch (Exception e) {
                    log.error("Failed to send initial status for run {}: {}", runId, e.getMessage());
                }
            }
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        runSubscribers.values().forEach(subscribers -> subscribers.remove(sessionId));
        runSubscribers.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        log.debug("Client {} disconnected", sessionId);
    }

    public void broadcastLogMessage(String runId, Map<String, Object> logData) {
        messagingTemplate.convertAndSend(
                "/topic/executions/" + runId + "/logs",
                logData
        );
    }

    public void broadcastStatusChange(String runId, RunStatus status) {
        messagingTemplate.convertAndSend(
                "/topic/executions/" + runId + "/logs",
                Map.of("type", "status", "runId", runId, "status", status)
        );
    }

    public boolean hasSubscribers(String runId) {
        Set<String> subscribers = runSubscribers.get(runId);
        return subscribers != null && !subscribers.isEmpty();
    }

    private String extractRunId(String destination) {
        // /topic/executions/{runId}/logs
        String[] parts = destination.split("/");
        if (parts.length >= 4) {
            return parts[3];
        }
        return null;
    }
}
