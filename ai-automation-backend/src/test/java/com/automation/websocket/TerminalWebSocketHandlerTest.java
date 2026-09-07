package com.automation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TerminalWebSocketHandlerTest {

    @Mock private WorkflowExecutionRegistry executionRegistry;
    @Mock private DockerClient dockerClient;
    @Mock private WebSocketSession session;

    private TerminalWebSocketHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new TerminalWebSocketHandler(executionRegistry, dockerClient, objectMapper);
    }

    @Test
    void afterConnectionEstablished_missingRunId_closesBadData() throws Exception {
        when(session.getUri()).thenReturn(URI.create("/ws/terminal"));

        handler.afterConnectionEstablished(session);

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.BAD_DATA.getCode()));
    }

    @Test
    void afterConnectionEstablished_noActiveExecution_closesPolicyViolation() throws Exception {
        when(session.getUri()).thenReturn(URI.create("/ws/terminal/run-123"));
        when(executionRegistry.getContainerIdForRun("run-123")).thenReturn(null);

        handler.afterConnectionEstablished(session);

        verify(session).close(argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
    }

    @Test
    void afterConnectionClosed_cleansUpSession() throws Exception {
        Map<String, Object> attrs = new HashMap<>();
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("ws-1");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        // Should not throw — no session to clean up
    }

    @Test
    void handleTextMessage_noTerminalSession_doesNothing() throws Exception {
        when(session.getId()).thenReturn("ws-unknown");

        handler.handleTextMessage(session, new TextMessage("input"));

        // No exception — silently ignored when no terminal session
    }

    @Test
    void handleTransportError_logsWithoutThrowing() {
        when(session.getId()).thenReturn("ws-1");

        handler.handleTransportError(session, new RuntimeException("network error"));

        // Should not throw
    }
}
