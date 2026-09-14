package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ExecutionBroadcaster broadcaster;

    private static final String RUN_ID = "run-123";
    private static final String EXPECTED_TOPIC = "/topic/executions/run-123/logs";

    // --- broadcastStepResult ---

    @Test
    void broadcastStepResult_sendsCorrectTopicAndPayload() {
        StepResult step = StepResult.builder()
                .stepIndex(2)
                .status(StepStatus.COMPLETED)
                .input("test input")
                .output("test output")
                .durationMs(150L)
                .build();

        broadcaster.broadcastStepResult(RUN_ID, step);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("stepIndex", 2);
        assertThat(payload).containsEntry("status", StepStatus.COMPLETED);
        assertThat(payload).containsEntry("input", "test input");
        assertThat(payload).containsEntry("output", "test output");
        assertThat(payload).containsEntry("durationMs", 150L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void broadcastStepResult_includesBlockLabelsFromMetadata_whenPresent() {
        StepResult labelled = StepResult.builder().stepIndex(1).status(StepStatus.RUNNING)
                .metadata(new HashMap<>(Map.of("blockType", "shell", "blockName", "Build", "tokens", 3))).build();
        StepResult unlabelled = StepResult.builder().stepIndex(2).status(StepStatus.RUNNING)
                .metadata(new HashMap<>(Map.of("blockName", "  "))).build();

        broadcaster.broadcastStepResult(RUN_ID, labelled);
        broadcaster.broadcastStepResult(RUN_ID, unlabelled);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());
        assertThat(captor.getAllValues().get(0))
                .containsEntry("blockType", "shell").containsEntry("blockName", "Build").doesNotContainKey("tokens");
        assertThat(captor.getAllValues().get(1)).doesNotContainKeys("blockType", "blockName");
    }

    @Test
    void broadcastStepResult_nullFieldsDefaultToEmptyAndZero() {
        StepResult step = StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.PENDING)
                .input(null)
                .output(null)
                .durationMs(null)
                .build();

        broadcaster.broadcastStepResult(RUN_ID, step);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("input", "");
        assertThat(payload).containsEntry("output", "");
        assertThat(payload).containsEntry("durationMs", 0L);
    }

    @Test
    void broadcastStepResult_exceptionDoesNotPropagate() {
        doThrow(new RuntimeException("WebSocket down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        StepResult step = StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.FAILED)
                .build();

        // Should not throw
        broadcaster.broadcastStepResult(RUN_ID, step);

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- broadcastRunStatus ---

    @Test
    void broadcastRunStatus_sendsCorrectPayload() {
        broadcaster.broadcastRunStatus(RUN_ID, RunStatus.COMPLETED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("type", "status");
        assertThat(payload).containsEntry("runId", RUN_ID);
        assertThat(payload).containsEntry("status", RunStatus.COMPLETED);
    }

    @Test
    void broadcastRunStatus_exceptionDoesNotPropagate() {
        doThrow(new RuntimeException("WebSocket down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        broadcaster.broadcastRunStatus(RUN_ID, RunStatus.FAILED);

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- broadcastLogMessage ---

    @Test
    void broadcastLogMessage_sendsDataWithTypeRunIdAndTimestamp() {
        Map<String, Object> data = new HashMap<>();
        data.put("message", "Step completed");
        data.put("level", "INFO");

        broadcaster.broadcastLogMessage(RUN_ID, "log", data);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("type", "log");
        assertThat(payload).containsEntry("runId", RUN_ID);
        assertThat(payload).containsEntry("message", "Step completed");
        assertThat(payload).containsEntry("level", "INFO");
        assertThat(payload).containsKey("timestamp");
    }

    @Test
    void broadcastLogMessage_doesNotMutateOriginalData() {
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");

        broadcaster.broadcastLogMessage(RUN_ID, "event", data);

        // Original map should not have type/runId/timestamp added
        assertThat(data).hasSize(1);
        assertThat(data).containsEntry("key", "value");
    }

    @Test
    void broadcastLogMessage_exceptionDoesNotPropagate() {
        doThrow(new RuntimeException("WebSocket down"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        broadcaster.broadcastLogMessage(RUN_ID, "error", Map.of("detail", "fail"));

        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void broadcastLogMessage_overridesConflictingKeysInData() {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "old-type");
        data.put("runId", "old-run-id");
        data.put("timestamp", "old-timestamp");
        data.put("extra", "kept");

        broadcaster.broadcastLogMessage(RUN_ID, "new-type", data);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("type", "new-type");
        assertThat(payload).containsEntry("runId", RUN_ID);
        assertThat(payload).containsEntry("extra", "kept");
        assertThat(payload.get("timestamp")).isNotEqualTo("old-timestamp");
    }

    @Test
    void broadcastLogMessage_handlesEmptyDataMap() {
        broadcaster.broadcastLogMessage(RUN_ID, "heartbeat", new HashMap<>());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        Map<String, Object> payload = captor.getValue();
        assertThat(payload).containsEntry("type", "heartbeat");
        assertThat(payload).containsEntry("runId", RUN_ID);
        assertThat(payload).containsKey("timestamp");
        assertThat(payload).hasSize(3);
    }

    @Test
    void broadcastStepResult_payloadContainsExactlyFiveEntries() {
        StepResult step = StepResult.builder()
                .stepIndex(1)
                .status(StepStatus.COMPLETED)
                .input("in")
                .output("out")
                .durationMs(42L)
                .build();

        broadcaster.broadcastStepResult(RUN_ID, step);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq(EXPECTED_TOPIC), captor.capture());

        assertThat(captor.getValue()).hasSize(5)
                .containsOnlyKeys("stepIndex", "status", "input", "output", "durationMs");
    }
}
