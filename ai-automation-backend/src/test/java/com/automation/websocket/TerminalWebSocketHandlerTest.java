package com.automation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    /**
     * TODO bb86b7ef: a resize message without {@code cols}/{@code rows} used to throw inside the parser
     * branch, the broad catch swallowed it, and the JSON went into the container shell as keystrokes.
     * It resizes to the defaults now and writes nothing.
     */
    @Test
    void handleTextMessage_resizeWithoutSize_usesTheDefaultsAndWritesNothing() throws Exception {
        TerminalSession terminal = connectedTerminal("ws-1");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\"}"));

        verify(terminal).resize(80, 24);
        verify(terminal, never()).write(anyString());
    }

    @Test
    void handleTextMessage_resizeWithSize_resizesAndWritesNothing() throws Exception {
        TerminalSession terminal = connectedTerminal("ws-1");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));

        verify(terminal).resize(120, 40);
        verify(terminal, never()).write(anyString());
    }

    /** Sizes of zero or below fall back to the defaults, absurd sizes are capped before they reach Docker. */
    @Test
    void handleTextMessage_resizeOutOfRange_isClampedBeforeDocker() throws Exception {
        TerminalSession terminal = connectedTerminal("ws-1");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":0,\"rows\":-5}"));
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"resize\",\"cols\":4294967297,\"rows\":5000}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"resize\",\"cols\":\"wide\",\"rows\":1}"));

        verify(terminal).resize(80, 24);
        verify(terminal).resize(1000, 1000);
        verify(terminal).resize(80, 1);
        verify(terminal, never()).write(anyString());
    }

    @Test
    void handleTextMessage_inputThatIsNoControlMessage_goesToTheShellUnchanged() throws Exception {
        TerminalSession terminal = connectedTerminal("ws-1");

        handler.handleTextMessage(session, new TextMessage("{not json"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"other\"}"));
        handler.handleTextMessage(session, new TextMessage("ls -la\r"));

        verify(terminal).write("{not json");
        verify(terminal).write("{\"type\":\"other\"}");
        verify(terminal).write("ls -la\r");
        verify(terminal, never()).resize(anyInt(), anyInt());
    }

    @Test
    void handleTransportError_logsWithoutThrowing() {
        when(session.getId()).thenReturn("ws-1");

        handler.handleTransportError(session, new RuntimeException("network error"));

        // Should not throw
    }

    /** Registers a mocked terminal for {@code sessionId}, as a successful connect would. */
    @SuppressWarnings("unchecked")
    private TerminalSession connectedTerminal(String sessionId) {
        TerminalSession terminal = mock(TerminalSession.class);
        when(session.getId()).thenReturn(sessionId);
        ((Map<String, TerminalSession>) ReflectionTestUtils.getField(handler, "sessions")).put(sessionId, terminal);
        return terminal;
    }
}
