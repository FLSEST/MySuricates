package com.automation.service.dashboard;

import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DashboardRunBroadcasterTest {

    @Mock private SimpMessagingTemplate messagingTemplate;

    private DashboardRunBroadcaster broadcaster() {
        return new DashboardRunBroadcaster(messagingTemplate);
    }

    private static WorkflowRunCompletedEvent event(String workflowId) {
        return new WorkflowRunCompletedEvent(new Object(), "run-1", workflowId, "Nightly QA", "proj-1", "/tmp/p",
                RunStatus.COMPLETED, Map.of("gate_status", "GREEN", "secret", "key-1"), Map.of(), Map.of());
    }

    @Test
    void sends_ids_and_status_only() {
        broadcaster().onWorkflowRunCompleted(event("wf-a1"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/dashboard/runs"), payload.capture());
        assertThat(payload.getValue()).isEqualTo(Map.of(
                "type", "run_completed", "runId", "run-1", "workflowId", "wf-a1", "status", "COMPLETED"));
    }

    @Test
    void stays_silent_without_a_workflow_id_and_never_breaks_the_run() {
        broadcaster().onWorkflowRunCompleted(event(null));
        verifyNoInteractions(messagingTemplate);

        doThrow(new IllegalStateException("broker down")).when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));
        assertThatCode(() -> broadcaster().onWorkflowRunCompleted(event("wf-a1"))).doesNotThrowAnyException();
    }
}
