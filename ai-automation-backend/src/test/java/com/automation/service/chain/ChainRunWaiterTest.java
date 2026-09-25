package com.automation.service.chain;

import com.automation.config.ChainConfig;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Locks the wait/timeout/cancel semantics of chain entries before extraction (Audit-TODO 050b85d8). */
@ExtendWith(MockitoExtension.class)
class ChainRunWaiterTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private StepResultRepository stepResultRepository;

    private ChainConfig chainConfig;
    private ChainRunWaiter waiter;

    @BeforeEach
    void setUp() {
        chainConfig = new ChainConfig();
        chainConfig.setWorkflowTimeoutMinutes(180);
        waiter = new ChainRunWaiter(chainConfig, workflowRunRepository, stepResultRepository);
    }

    private static WorkflowRun run(RunStatus status) {
        return WorkflowRun.builder().id("r1").status(status).build();
    }

    @Test
    void waitForCompletion_terminalStatus_returnsImmediately() {
        when(workflowRunRepository.findById("r1")).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        assertThat(waiter.waitForCompletion("r1", new AtomicBoolean(false)).getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(workflowRunRepository, times(1)).findById("r1");
    }

    @Test
    void waitForCompletion_pollsWhilePendingRunningOrPaused_thenReturns() {
        when(workflowRunRepository.findById("r1")).thenReturn(
                Optional.of(run(RunStatus.PENDING)), Optional.of(run(RunStatus.RUNNING)),
                Optional.of(run(RunStatus.PAUSED)), Optional.of(run(RunStatus.FAILED)));

        assertThat(waiter.waitForCompletion("r1", null).getStatus()).isEqualTo(RunStatus.FAILED);
        verify(workflowRunRepository, times(4)).findById("r1");
    }

    @Test
    void waitForCompletion_cancelFlag_throwsCancellation_beforeReadingRun() {
        assertThatThrownBy(() -> waiter.waitForCompletion("r1", new AtomicBoolean(true)))
                .isInstanceOf(CancellationException.class)
                .hasMessage("Chain execution cancelled");
        verify(workflowRunRepository, never()).findById("r1");
    }

    @Test
    void waitForCompletion_zeroTimeout_throwsTimeoutWithoutPolling() {
        chainConfig.setWorkflowTimeoutMinutes(0);

        assertThatThrownBy(() -> waiter.waitForCompletion("r1", null))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Workflow timed out after 0 minutes");
        verify(workflowRunRepository, never()).findById("r1");
    }

    @Test
    void extractOutput_noSteps_null_lastStepOutputOtherwise() {
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r1")).thenReturn(List.of());
        assertThat(waiter.extractOutput(run(RunStatus.COMPLETED))).isNull();

        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("r1")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).output("first").build(),
                StepResult.builder().stepIndex(1).output("last").build()));
        assertThat(waiter.extractOutput(run(RunStatus.COMPLETED))).isEqualTo("last");
    }

    @Test
    void waitForCompletion_entryTimeout_overridesTheGlobalLimit() {
        assertThatThrownBy(() -> waiter.waitForCompletion("r1", new AtomicBoolean(false), 0))
                .hasMessage("Workflow timed out after 0 minutes");
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    void waitForCompletion_withoutEntryTimeout_usesTheGlobalLimit() {
        chainConfig.setWorkflowTimeoutMinutes(0);

        assertThatThrownBy(() -> waiter.waitForCompletion("r1", new AtomicBoolean(false), null))
                .hasMessage("Workflow timed out after 0 minutes");
    }
}
