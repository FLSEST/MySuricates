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
import org.springframework.messaging.support.GenericMessage;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogWebSocketHandlerTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private WorkflowRunRepository runRepository;

    private LogWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LogWebSocketHandler(messagingTemplate, runRepository);
    }

    @Test
    void broadcastLogMessage_sendsToCorrectTopic() {
        Map<String, Object> logData = Map.of("type", "log", "message", "step completed");

        handler.broadcastLogMessage("run-1", logData);

        verify(messagingTemplate).convertAndSend("/topic/executions/run-1/logs", logData);
    }

    @Test
    void broadcastStatusChange_sendsStatusMap() {
        handler.broadcastStatusChange("run-1", RunStatus.COMPLETED);

        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/executions/run-1/logs"), captor.capture());
        assertThat(captor.getValue().get("type")).isEqualTo("status");
        assertThat(captor.getValue().get("status")).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void hasSubscribers_noSubscribers_returnsFalse() {
        assertThat(handler.hasSubscribers("run-1")).isFalse();
    }
}
