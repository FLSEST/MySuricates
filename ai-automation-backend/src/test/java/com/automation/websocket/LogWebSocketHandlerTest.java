package com.automation.websocket;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogWebSocketHandlerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private WorkflowRunRepository runRepository;

    private LogWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LogWebSocketHandler(messagingTemplate, runRepository);
    }

    /**
     * TODO a47e0d0f: this class is not a raw {@code WebSocketHandler} and needs no entry in
     * {@code RawWebSocketConfig} — it reacts to STOMP session events published for every
     * subscription on the {@code /ws} endpoint, which {@code WebSocketConfig} registers with
     * {@code WebSocketAuthInterceptor} already applied at the handshake (pinned by
     * {@code WebSocketConfigTest#should_addAuthInterceptor_when_registeringStompEndpoints}). Pins
     * the two annotations that make that wiring work, so removing either fails loudly here instead
     * of quietly stopping the initial-status-on-subscribe feature.
     */
    @Test
    void should_beWiredAsStompSessionEventListener_notARawWebSocketHandler() throws NoSuchMethodException {
        assertThat(LogWebSocketHandler.class.isAnnotationPresent(org.springframework.stereotype.Component.class))
                .as("must be a Spring bean for its @EventListener methods to be picked up")
                .isTrue();
        assertThat(LogWebSocketHandler.class.getMethod("handleSubscribe", SessionSubscribeEvent.class)
                .isAnnotationPresent(org.springframework.context.event.EventListener.class))
                .as("handleSubscribe must react to Spring's STOMP session events")
                .isTrue();
        assertThat(LogWebSocketHandler.class.getMethod("handleDisconnect", SessionDisconnectEvent.class)
                .isAnnotationPresent(org.springframework.context.event.EventListener.class))
                .as("handleDisconnect must react to Spring's STOMP session events")
                .isTrue();
    }

    /**
     * TODO 7adaf187: this class must not grow its own broadcast helpers again —
     * {@code ExecutionBroadcaster}/{@code RunLiveLogService} own publishing to the run topic.
     */
    @Test
    void should_notExposeBroadcastHelpers_soPublishingStaysWithExecutionBroadcaster() {
        assertThat(LogWebSocketHandler.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("broadcastLogMessage", "broadcastStatusChange");
    }

    @Test
    void hasSubscribers_noSubscribers_returnsFalse() {
        assertThat(handler.hasSubscribers("run-1")).isFalse();
    }

    @Test
    void handleSubscribe_validLogTopic_registersSubscriberAndSendsInitialStatus() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-42");
        run.setStatus(RunStatus.RUNNING);
        run.setSteps(Collections.emptyList());
        when(runRepository.findById("run-42")).thenReturn(Optional.of(run));

        SessionSubscribeEvent event = subscribeEvent("/topic/executions/run-42/logs", "session-A");

        handler.handleSubscribe(event);

        assertThat(handler.hasSubscribers("run-42")).isTrue();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/executions/run-42/logs"), (Object) captor.capture());
        assertThat(captor.getValue().get("type")).isEqualTo("status");
        assertThat(captor.getValue().get("runId")).isEqualTo("run-42");
        assertThat(captor.getValue().get("status")).isEqualTo(RunStatus.RUNNING);
        assertThat(captor.getValue().get("stepsCompleted")).isEqualTo(0);
    }

    @Test
    void handleSubscribe_runNotFound_registersSubscriberButSendsNothing() {
        when(runRepository.findById("missing-run")).thenReturn(Optional.empty());

        SessionSubscribeEvent event = subscribeEvent("/topic/executions/missing-run/logs", "session-B");

        handler.handleSubscribe(event);

        assertThat(handler.hasSubscribers("missing-run")).isTrue();
        verify(messagingTemplate, never()).convertAndSend(any(String.class), (Object) any(Map.class));
    }

    @Test
    void handleSubscribe_unrelatedTopic_isIgnored() {
        SessionSubscribeEvent event = subscribeEvent("/topic/other/something", "session-C");

        handler.handleSubscribe(event);

        assertThat(handler.hasSubscribers("something")).isFalse();
        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(runRepository);
    }

    @Test
    void handleSubscribe_nullDestination_isIgnored() {
        SessionSubscribeEvent event = subscribeEvent(null, "session-D");

        handler.handleSubscribe(event);

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(runRepository);
    }

    @Test
    void handleSubscribe_repositoryThrows_doesNotPropagate() {
        when(runRepository.findById("run-x")).thenThrow(new RuntimeException("db down"));

        SessionSubscribeEvent event = subscribeEvent("/topic/executions/run-x/logs", "session-E");

        handler.handleSubscribe(event); // must not throw

        assertThat(handler.hasSubscribers("run-x")).isTrue();
        verify(messagingTemplate, never()).convertAndSend(any(String.class), (Object) any(Map.class));
    }

    @Test
    void handleDisconnect_removesSessionFromAllRuns() {
        // Subscribe two sessions to two different runs
        WorkflowRun run1 = new WorkflowRun();
        run1.setId("run-1");
        run1.setStatus(RunStatus.RUNNING);
        run1.setSteps(Collections.emptyList());
        WorkflowRun run2 = new WorkflowRun();
        run2.setId("run-2");
        run2.setStatus(RunStatus.RUNNING);
        run2.setSteps(Collections.emptyList());
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run1));
        when(runRepository.findById("run-2")).thenReturn(Optional.of(run2));

        handler.handleSubscribe(subscribeEvent("/topic/executions/run-1/logs", "session-X"));
        handler.handleSubscribe(subscribeEvent("/topic/executions/run-2/logs", "session-X"));
        handler.handleSubscribe(subscribeEvent("/topic/executions/run-2/logs", "session-Y"));

        assertThat(handler.hasSubscribers("run-1")).isTrue();
        assertThat(handler.hasSubscribers("run-2")).isTrue();

        handler.handleDisconnect(disconnectEvent("session-X"));

        // run-1 had only session-X -> empty entry should be removed
        assertThat(handler.hasSubscribers("run-1")).isFalse();
        // run-2 still has session-Y
        assertThat(handler.hasSubscribers("run-2")).isTrue();
    }

    @Test
    void handleDisconnect_unknownSession_doesNothing() {
        handler.handleDisconnect(disconnectEvent("never-seen"));

        assertThat(handler.hasSubscribers("run-1")).isFalse();
    }

    // ----- helpers -----

    private SessionSubscribeEvent subscribeEvent(String destination, String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(this, message);
    }

    private SessionDisconnectEvent disconnectEvent(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, CloseStatus.NORMAL);
    }
}
