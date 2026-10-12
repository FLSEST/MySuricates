package com.automation.websocket;

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

/**
 * Tracks per-run subscribers on {@code /topic/executions/{runId}/logs} and sends a newly
 * subscribed client its current status immediately, instead of making it wait for the next event.
 *
 * <p><b>Not a raw {@code WebSocketHandler}</b> (TODO a47e0d0f found it unregistered anywhere and
 * asked whether that made it dead code or a forgotten registration — neither): it is a plain
 * {@code @Component} whose {@code @EventListener} methods react to the STOMP session lifecycle
 * events ({@link SessionSubscribeEvent}, {@link SessionDisconnectEvent}) that Spring's message
 * broker publishes for every subscription on the {@code /ws} STOMP endpoint, which {@code
 * WebSocketConfig} registers with {@code WebSocketAuthInterceptor} already applied at the HTTP
 * handshake (pinned by {@code WebSocketConfigTest#should_addAuthInterceptor_when_registeringStompEndpoints}
 * and, for the listener wiring itself, {@code
 * LogWebSocketHandlerTest#should_beWiredAsStompSessionEventListener_notARawWebSocketHandler}).
 * There is nothing to add to {@code RawWebSocketConfig}, which registers raw handlers such as
 * {@code TerminalWebSocketHandler} on their own path instead — a different, unrelated mechanism.
 * The frontend confirms a live consumer: {@code useWebSocket.ts} subscribes to exactly this topic.
 *
 * <p>This class does <b>not</b> broadcast run output itself: {@code ExecutionBroadcaster} and
 * {@code RunLiveLogService} publish to the same topic and are what every real call site uses. Two
 * unused {@code broadcastLogMessage}/{@code broadcastStatusChange} duplicates were removed with
 * TODO 7adaf187; do not reintroduce them here.
 */
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
                        messagingTemplate.convertAndSend(destination, (Object) Map.of(
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
