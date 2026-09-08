package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgenticEventBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    @SuppressWarnings("unchecked")
    void broadcast_sendsTypeStepIndexAndDataToRunLogTopic_inOrder() {
        new AgenticEventBroadcaster(messagingTemplate).broadcast("run-1", 2, "agentic_reasoned",
                new LinkedHashMap<>(Map.of("iteration", 1)));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/executions/run-1/logs"), payload.capture());
        Map<String, Object> message = (Map<String, Object>) payload.getValue();
        assertThat(message.keySet()).containsExactly("type", "stepIndex", "iteration");
        assertThat(message).containsEntry("type", "agentic_reasoned").containsEntry("stepIndex", 2);
    }

    @Test
    void broadcast_messagingFailure_isSwallowed() {
        doThrow(new RuntimeException("ws down")).when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> new AgenticEventBroadcaster(messagingTemplate).broadcast("run-1", 0, "agentic_started", Map.of()))
                .doesNotThrowAnyException();
    }
}
