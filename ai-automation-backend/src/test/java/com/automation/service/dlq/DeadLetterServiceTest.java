package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        Workflow workflow = Workflow.builder().id("wf-1").name("My Workflow").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .workflow(workflow)
                .workflowName("My Workflow")
                .error(Map.of("message", "Step 0 (shell) failed: command not found"))
                .steps(List.of(
                        StepResult.builder().stepIndex(0).status(StepStatus.FAILED).build()
                ))
                .build();

        DeadLetterEntry result = service.recordFailure(run);

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
        WorkflowRun run = WorkflowRun.builder()
                .id("run-2")
                .steps(List.of())
                .build();

        DeadLetterEntry saved = service.recordFailure(run);

        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getFailedStepIndex()).isNull();
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

        assertThatThrownBy(() -> service.findById("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
