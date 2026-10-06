package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ElementNotFoundException;
import com.automation.repository.DeadLetterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    @Mock private DeadLetterRepository repository;

    private DeadLetterService service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterService(repository);
    }

    @Test
    void recordFailure_capturesRunDetails() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeadLetterEntry result = service.recordFailure(failedEvent("run-1",
                Map.of("step", 0, "blockType", "shell", "message", "Step 0 (shell) failed: command not found")));

        ArgumentCaptor<DeadLetterEntry> captor = ArgumentCaptor.forClass(DeadLetterEntry.class);
        verify(repository).save(captor.capture());
        DeadLetterEntry saved = captor.getValue();
        assertThat(saved.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(saved.getWorkflowId()).isEqualTo("wf-1");
        assertThat(saved.getWorkflowName()).isEqualTo("My Workflow");
        assertThat(saved.getErrorMessage()).contains("command not found");
        assertThat(saved.getFailedStepIndex()).isEqualTo(0);
        assertThat(saved.getStatus()).isEqualTo(DeadLetterEntry.Status.OPEN);
        assertThat(saved.getRetryCount()).isEqualTo(0);
        assertThat(saved.getLastFailureAt()).isNotNull();
        assertThat(result).isSameAs(saved);
    }

    @Test
    void recordFailure_handlesMissingError() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeadLetterEntry saved = service.recordFailure(failedEvent("run-2", null));

        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getFailedStepIndex()).isNull();
    }

    @Test
    void onWorkflowRunCompleted_recordsFailedRun() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.onWorkflowRunCompleted(failedEvent("run-3", Map.of("message", "boom")));

        verify(repository).save(argThat(e -> "run-3".equals(e.getWorkflowRunId())));
    }

    @Test
    void onWorkflowRunCompleted_ignoresRunsThatDidNotFail() {
        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.CANCELLED)) {
            service.onWorkflowRunCompleted(event("run-4", status, null));
        }

        verifyNoInteractions(repository);
    }

    /** Synchronous listeners run one after another — an error here must not cut off the following ones. */
    @Test
    void onWorkflowRunCompleted_swallowsRepositoryFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("dlq down"));

        assertThatCode(() -> service.onWorkflowRunCompleted(failedEvent("run-5", Map.of("message", "boom"))))
                .doesNotThrowAnyException();
    }

    private static WorkflowRunCompletedEvent failedEvent(String runId, Map<String, Object> error) {
        return event(runId, RunStatus.FAILED, error);
    }

    private static WorkflowRunCompletedEvent event(String runId, RunStatus status, Map<String, Object> error) {
        return new WorkflowRunCompletedEvent(new Object(), runId, "wf-1", "My Workflow", "proj-1", "/tmp/p",
                status, Map.of(), Map.of(), error);
    }

    @Test
    void markRetried_incrementsCounterAndUpdatesStatus() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .id("d-1")
                .retryCount(2)
                .status(DeadLetterEntry.Status.OPEN)
                .build();
        when(repository.findById("d-1")).thenReturn(Optional.of(entry));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeadLetterEntry result = service.markRetried("d-1");

        assertThat(result.getRetryCount()).isEqualTo(3);
        assertThat(result.getStatus()).isEqualTo(DeadLetterEntry.Status.RETRIED);
    }

    @Test
    void discard_setsStatusToDiscarded() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .id("d-2")
                .status(DeadLetterEntry.Status.OPEN)
                .build();
        when(repository.findById("d-2")).thenReturn(Optional.of(entry));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.discard("d-2");

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.DISCARDED);
        verify(repository).save(entry);
    }

    @Test
    void findById_unknown_throws() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        // 2b73ff48: a 404 the UI translates instead of a 400 with free text
        assertThatThrownBy(() -> service.findById("missing"))
                .isInstanceOf(ElementNotFoundException.class)
                .hasMessage("DeadLetterEntry not found: missing");
    }
}
