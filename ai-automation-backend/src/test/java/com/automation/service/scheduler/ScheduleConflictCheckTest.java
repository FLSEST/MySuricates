package com.automation.service.scheduler;

import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ConflictBehavior;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.scheduler.ScheduleConflictCheck.SkipReason;
import com.automation.service.workflow.PendingAutoResumes;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduleConflictCheckTest {

    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
    private final ChainRunRepository chainRuns = mock(ChainRunRepository.class);
    private final PendingAutoResumes pendingAutoResumes = mock(PendingAutoResumes.class);
    private final ScheduleConflictCheck check = new ScheduleConflictCheck(workflowRuns, chainRuns, pendingAutoResumes);

    private static ScheduledWorkflow workflowSchedule(ConflictBehavior behavior) {
        return ScheduledWorkflow.builder().workflow(Workflow.builder().id("w-1").build()).conflictBehavior(behavior)
                .build();
    }

    private static ScheduledWorkflow chainSchedule() {
        return ScheduledWorkflow.builder().targetType(TargetType.CHAIN).chain(WorkflowChain.builder().id("c-1").build())
                .build();
    }

    @Test
    void busyStatesAreRunningAndPaused_notPending() {
        assertThat(ScheduleConflictCheck.BUSY_STATES).containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.PAUSED);
    }

    @Test
    void overlapNeverSkipsAndAsksNothing() {
        assertThat(check.reasonToSkip(workflowSchedule(ConflictBehavior.OVERLAP))).isNull();

        verifyNoInteractions(workflowRuns, chainRuns, pendingAutoResumes);
    }

    @Test
    void workflowWithBusyRunIsSkippedAsTargetBusy() {
        when(workflowRuns.existsByWorkflowIdAndStatusIn("w-1", ScheduleConflictCheck.BUSY_STATES)).thenReturn(true);

        SkipReason reason = check.reasonToSkip(workflowSchedule(ConflictBehavior.SKIP));

        assertThat(reason).isEqualTo(SkipReason.TARGET_BUSY);
        assertThat(reason.key).isEqualTo("target-busy");
        assertThat(reason.text).isEqualTo("previous run still in progress");
    }

    @Test
    void idleWorkflowMayStart() {
        assertThat(check.reasonToSkip(workflowSchedule(ConflictBehavior.SKIP))).isNull();

        verify(workflowRuns).existsByWorkflowIdAndStatusIn("w-1", ScheduleConflictCheck.BUSY_STATES);
    }

    @Test
    void pendingAutoResumeWinsAndIsAskedBeforeTheRunStatus() {
        when(pendingAutoResumes.forWorkflow("w-1")).thenReturn(true);
        when(workflowRuns.existsByWorkflowIdAndStatusIn(anyString(), any())).thenReturn(true);

        SkipReason reason = check.reasonToSkip(workflowSchedule(ConflictBehavior.SKIP));

        assertThat(reason).isEqualTo(SkipReason.AUTO_RESUME_PENDING);
        assertThat(reason.key).isEqualTo("auto-resume-pending");
        verify(workflowRuns, never()).existsByWorkflowIdAndStatusIn(anyString(), any());
    }

    @Test
    void withoutPendingResumeTheResumeIsAskedFirstThenTheRunStatus() {
        check.reasonToSkip(workflowSchedule(ConflictBehavior.SKIP));

        InOrder order = inOrder(pendingAutoResumes, workflowRuns);
        order.verify(pendingAutoResumes).forWorkflow("w-1");
        order.verify(workflowRuns).existsByWorkflowIdAndStatusIn("w-1", ScheduleConflictCheck.BUSY_STATES);
    }

    @Test
    void chainWithBusyRunIsSkipped_withoutAskingTheAutoResumer() {
        when(chainRuns.existsByChainIdAndStatusIn("c-1", ScheduleConflictCheck.BUSY_STATES)).thenReturn(true);

        assertThat(check.reasonToSkip(chainSchedule())).isEqualTo(SkipReason.TARGET_BUSY);

        verifyNoInteractions(pendingAutoResumes, workflowRuns);
    }

    @Test
    void idleChainMayStart() {
        assertThat(check.reasonToSkip(chainSchedule())).isNull();
    }

    @Test
    void missingTargetIsNeverBusy() {
        ScheduledWorkflow noWorkflow = ScheduledWorkflow.builder().build();
        ScheduledWorkflow noChain = ScheduledWorkflow.builder().targetType(TargetType.CHAIN).build();

        assertThat(check.reasonToSkip(noWorkflow)).isNull();
        assertThat(check.reasonToSkip(noChain)).isNull();
        verifyNoInteractions(workflowRuns, chainRuns, pendingAutoResumes);
    }
}
