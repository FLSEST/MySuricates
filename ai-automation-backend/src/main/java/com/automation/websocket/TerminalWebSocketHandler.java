package com.automation.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_SESSIONS_PER_CONTAINER = 3;

    private final WorkflowExecutor workflowExecutor;
    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;

    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> containerSessionCounts = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String runId = extractRunId(session);
        if (runId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("Missing runId"));
            return;
        }

        String containerId = workflowExecutor.getContainerIdForRun(runId);
        if (containerId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("No active execution for run: " + runId));
            return;
        }

        // Check session limit per container
        AtomicInteger count = containerSessionCounts.computeIfAbsent(containerId, k -> new AtomicInteger(0));
        if (count.get() >= MAX_SESSIONS_PER_CONTAINER) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Max terminal sessions reached"));
            return;
        }
        count.incrementAndGet();

        try {
            TerminalSession terminal = new TerminalSession(session, dockerClient, containerId);
            sessions.put(session.getId(), terminal);
            session.getAttributes().put("containerId", containerId);
            log.info("Terminal WebSocket connected: session={}, run={}, container={}", session.getId(), runId, containerId);
        } catch (Exception e) {
            count.decrementAndGet();
            log.error("Failed to start terminal session: {}", e.getMessage());
            session.close(CloseStatus.SERVER_ERROR.withReason("Failed to start terminal"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        TerminalSession terminal = sessions.get(session.getId());
        if (terminal == null) return;

        String payload = message.getPayload();

        // Check for JSON control messages (resize)
        if (payload.startsWith("{")) {
            try {
                JsonNode json = objectMapper.readTree(payload);
                if (json.has("type") && "resize".equals(json.get("type").asText())) {
                    int cols = json.get("cols").asInt(80);
                    int rows = json.get("rows").asInt(24);
                    terminal.resize(cols, rows);
                    return;
                }
            } catch (Exception e) {
                // Not a control message, treat as raw input
            }
        }

        // Forward raw input to container
        terminal.write(payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TerminalSession terminal = sessions.remove(session.getId());
        if (terminal != null) {
            terminal.destroy();
        }

        String containerId = (String) session.getAttributes().get("containerId");
        if (containerId != null) {
            AtomicInteger count = containerSessionCounts.get(containerId);
            if (count != null && count.decrementAndGet() <= 0) {
                containerSessionCounts.remove(containerId);
            }
        }

        log.info("Terminal WebSocket disconnected: session={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Terminal WebSocket transport error: session={}, error={}", session.getId(), exception.getMessage());
    }

    private String extractRunId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) return null;
        String path = uri.getPath();
        // Expected: /ws/terminal/{runId}
        String[] parts = path.split("/");
        if (parts.length >= 4 && "terminal".equals(parts[2])) {
            return parts[3];
        }
        return null;
    }
}
