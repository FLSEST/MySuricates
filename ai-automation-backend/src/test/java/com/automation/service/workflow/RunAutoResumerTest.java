package com.automation.service.workflow;

import com.automation.config.AutoResumeConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.testsupport.FakeTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TODO 90d10ba1: which interrupted runs are resumed automatically, and how (one at a time, once). */
@ExtendWith(MockitoExtension.class)
class RunAutoResumerTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ActivityLogService activityLog;
    @Mock private ScheduledExecutorService scheduler;

    private final AutoResumeConfig config = new AutoResumeConfig();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private RunAutoResumer resumer;
    private PendingAutoResumes pending;

    @BeforeEach
    void setUp() {
        config.setDelaySeconds(45);
        lenient().when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            delays.add(inv.getArgument(1));
            return null;
        });
        pending = new PendingAutoResumes(runRepository, workflowRepository);
        resumer = new RunAutoResumer(runRepository, workflowRepository, workflowExecutor, activityLog, config,
                FakeTransactions.manager(), scheduler, pending);
    }

    private static Workflow workflow(String id, boolean optIn) {
        Map<String, Object> metadata = optIn ? Map.of("autoResume", Map.of("enabled", true)) : Map.of();
        return Workflow.builder().id(id).name("wf " + id).metadata(new HashMap<>(metadata)).build();
    }

    private static WorkflowRun interrupted(String runId, Workflow workflow, String from) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RunInterruption.FROM_KEY, from);
        metadata.put(RunInterruption.BY_KEY, "RESTART");
        return WorkflowRun.builder().id(runId).workflow(workflow).status(RunStatus.INTERRUPTED)
                .triggerSource(TriggerSource.MANUAL).startedAt(Instant.now()).metadata(metadata).build();
    }

    private void stored(WorkflowRun run) {
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));
    }

    private void storedWorkflow(Workflow workflow) {
        when(workflowRepository.findById(workflow.getId())).thenReturn(Optional.of(workflow));
    }

    /** Runs the task the resumer scheduled last and returns how many are scheduled now. */
    private void runScheduled() {
        scheduled.get(scheduled.size() - 1).run();
    }

    // ── opt-in and eligibility ───────────────────────────────────────────────

    @Test
    void optedIn_onlyForAnExplicitTrue() {
        assertThat(RunAutoResumer.optedIn(Map.of("autoResume", Map.of("enabled", true)))).isTrue();
        assertThat(RunAutoResumer.optedIn(Map.of("autoResume", Map.of("enabled", false)))).isFalse();
        assertThat(RunAutoResumer.optedIn(Map.of("autoResume", Map.of("enabled", "true")))).isFalse();
        assertThat(RunAutoResumer.optedIn(Map.of("autoResume", true))).isFalse();
        assertThat(RunAutoResumer.optedIn(Map.of())).isFalse();
        assertThat(RunAutoResumer.optedIn(null)).isFalse();
    }

    @Test
    void skipReason_onlyAFormerlyRunningTopLevelRunWithoutAttemptQualifies() {
        Workflow wf = workflow("wf-1", true);
        assertThat(RunAutoResumer.skipReason(interrupted("r", wf, "RUNNING"))).isNull();

        assertThat(RunAutoResumer.skipReason(null)).isNotNull();
        WorkflowRun completed = interrupted("r", wf, "RUNNING");
        completed.setStatus(RunStatus.COMPLETED);
        assertThat(RunAutoResumer.skipReason(completed)).contains("COMPLETED");
        assertThat(RunAutoResumer.skipReason(interrupted("r", wf, "PAUSED"))).contains("not running");
        WorkflowRun legacy = interrupted("r", wf, "RUNNING");
        legacy.setMetadata(new HashMap<>());     // marked before this existed, or by someone else
        assertThat(RunAutoResumer.skipReason(legacy)).contains("not running");

        WorkflowRun attempted = interrupted("r", wf, "RUNNING");
        attempted.getMetadata().put(RunAutoResumer.ATTEMPTS_KEY, 1);
        assertThat(RunAutoResumer.skipReason(attempted)).contains("already");

        for (TriggerSource nested : List.of(TriggerSource.CHAIN, TriggerSource.SUB_WORKFLOW)) {
            WorkflowRun run = interrupted("r", wf, "RUNNING");
            run.setTriggerSource(nested);
            assertThat(RunAutoResumer.skipReason(run)).as(nested.name()).contains("parent");
        }
        WorkflowRun chainEntryResumedByHand = interrupted("r", wf, "RUNNING");
        chainEntryResumedByHand.setTriggerSource(TriggerSource.RESUME);
        chainEntryResumedByHand.setChainRunId("chain-run-1");
        assertThat(RunAutoResumer.skipReason(chainEntryResumedByHand)).contains("parent");
        WorkflowRun childResumedByHand = interrupted("r", wf, "RUNNING");
        childResumedByHand.setTriggerSource(TriggerSource.RESUME);
        childResumedByHand.setInputContext(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"));
        assertThat(RunAutoResumer.skipReason(childResumedByHand)).contains("parent");
    }

    // ── flow ─────────────────────────────────────────────────────────────────

    @Test
    void switchedOff_nothingIsScheduled() {
        config.setEnabled(false);

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));

        verifyNoInteractions(scheduler, runRepository, workflowExecutor);
        assertThat(resumer.isIdle()).isTrue();
    }

    @Test
    void optedInRun_countsTheAttemptFirst_thenResumesAsAutoResume_afterTheDelay() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME)).thenReturn(run);

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));

        assertThat(delays).containsExactly(45L);
        verifyNoInteractions(workflowExecutor);        // nothing before the delay
        assertThat(resumer.isIdle()).isFalse();

        runScheduled();

        InOrder order = inOrder(runRepository, workflowExecutor);
        ArgumentCaptor<WorkflowRun> saved = ArgumentCaptor.forClass(WorkflowRun.class);
        order.verify(runRepository).save(saved.capture());
        order.verify(workflowExecutor).resumeFromStep("r1", TriggerSource.AUTO_RESUME);
        assertThat(saved.getValue().getMetadata()).containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1)
                .containsEntry(RunInterruption.FROM_KEY, "RUNNING");
        verify(activityLog).info(eq(LogCategory.EXECUTION), contains("Auto-resume: continued"),
                eq("RunAutoResumer"), eq("r1"), eq("wf-1"));
        assertThat(resumer.isIdle()).isTrue();
    }

    /** fe06ebf9: the resumer must not wrap resumeFromStep (which starts the run thread) in its transaction. */
    @Test
    void resumesOutsideATransaction() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);
        java.util.concurrent.atomic.AtomicBoolean inTransaction = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME)).thenAnswer(inv -> {
            inTransaction.set(FakeTransactions.active());
            return run;
        });

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));
        runScheduled();

        assertThat(inTransaction).as("resumeFromStep() ran inside a transaction").isFalse();
    }

    @Test
    void workflowWithoutOptIn_orDeleted_isLeftAlone() {
        Workflow plain = workflow("wf-1", false);
        WorkflowRun r1 = interrupted("r1", plain, "RUNNING");
        WorkflowRun r2 = interrupted("r2", workflow("wf-gone", true), "RUNNING");
        stored(r1);
        stored(r2);
        storedWorkflow(plain);
        when(workflowRepository.findById("wf-gone")).thenReturn(Optional.empty());   // soft-deleted

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1", "r2"), RunInterruption.Cause.STANDBY));
        runScheduled();

        verify(runRepository, never()).save(any());
        verifyNoInteractions(workflowExecutor, activityLog);
        assertThat(scheduled).hasSize(1);          // skipped runs cost no further wait
        assertThat(resumer.isIdle()).isTrue();
    }

    @Test
    void refusedByAdmission_staysCounted_isLogged_andTheNextOneWaitsItsTurn() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun r1 = interrupted("r1", wf, "RUNNING");
        WorkflowRun r2 = interrupted("r2", wf, "RUNNING");
        stored(r1);
        stored(r2);
        storedWorkflow(wf);
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME))
                .thenThrow(new HardeningException(ErrorCode.UNATTENDED_REQUIRES_SEAL, "needs a seal"));
        when(workflowExecutor.resumeFromStep("r2", TriggerSource.AUTO_RESUME)).thenReturn(r2);

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1", "r2"), RunInterruption.Cause.RESTART));
        runScheduled();

        assertThat(r1.getMetadata()).containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1);
        verify(activityLog).warn(eq(LogCategory.EXECUTION), contains("Auto-resume refused"),
                eq("RunAutoResumer"), eq("r1"), eq("wf-1"));
        verify(workflowExecutor, never()).resumeFromStep(eq("r2"), any());
        assertThat(delays).containsExactly(45L, 45L);   // r2 waits its own delay

        runScheduled();

        verify(workflowExecutor).resumeFromStep("r2", TriggerSource.AUTO_RESUME);
        assertThat(resumer.isIdle()).isTrue();
    }

    @Test
    void fullRetry_marksTheInterruptedRunWithTheNewRun() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME))
                .thenReturn(WorkflowRun.builder().id("r2").build());   // no finished block: a new run

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.STANDBY));
        runScheduled();

        assertThat(run.getMetadata()).containsEntry(RunAutoResumer.RESTARTED_AS_KEY, "r2")
                .containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1);
        verify(activityLog).info(eq(LogCategory.EXECUTION), contains("as new run r2"),
                eq("RunAutoResumer"), eq("r1"), eq("wf-1"));
    }

    @Test
    void carryOver_onlyForAutomaticResumes() {
        WorkflowRun run = interrupted("r1", workflow("wf-1", true), "RUNNING");
        run.getMetadata().put(RunAutoResumer.ATTEMPTS_KEY, 1);

        assertThat(RunAutoResumer.carryOver(run, TriggerSource.AUTO_RESUME))
                .containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1).containsEntry(RunAutoResumer.RESUMED_FROM_KEY, "r1");
        assertThat(RunAutoResumer.carryOver(run, TriggerSource.RESUME)).isNull();
    }

    @Test
    void secondAnnouncementOfTheSameRun_isNotQueuedTwice_andASecondAttemptIsRefused() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));
        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.STANDBY));
        assertThat(scheduled).hasSize(1);
        runScheduled();
        verify(workflowExecutor).resumeFromStep("r1", TriggerSource.AUTO_RESUME);

        // The resumed run is interrupted again and announced again: the counter keeps it for a person.
        run.setStatus(RunStatus.INTERRUPTED);
        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.STANDBY));
        runScheduled();

        verify(workflowExecutor).resumeFromStep(anyString(), any());   // still exactly once
    }

    // ── PendingAutoResumes: the scheduler's view (TODO 070ccc3d) ───────────

    /** The query of {@link PendingAutoResumes#forWorkflow}: the INTERRUPTED ones among these runs. */
    private void interruptedRunsOf(String workflowId, WorkflowRun... runs) {
        when(runRepository.findByWorkflowIdAndStatusAndCompletedAtGreaterThanEqual(eq(workflowId),
                eq(RunStatus.INTERRUPTED), any(Instant.class)))
                .thenAnswer(inv -> List.of(runs).stream().filter(r -> r.getStatus() == RunStatus.INTERRUPTED).toList());
    }

    @Test
    void pending_fromTheMarking_throughTheAttempt_untilTheRunIsRunning() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);
        interruptedRunsOf("wf-1", run);
        List<Boolean> duringAttempt = new ArrayList<>();
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME)).thenAnswer(inv -> {
            duringAttempt.add(pending.forWorkflow("wf-1"));   // attempt already counted, not RUNNING yet
            run.setStatus(RunStatus.RUNNING);
            return run;
        });

        assertThat(pending.forWorkflow("wf-1")).as("marked, event not there yet").isTrue();
        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));
        assertThat(pending.forWorkflow("wf-1")).as("waiting for the delay").isTrue();

        runScheduled();

        assertThat(duringAttempt).containsExactly(true);
        assertThat(pending.forWorkflow("wf-1")).as("RUNNING now — the busy check takes over").isFalse();
    }

    @Test
    void pending_endsWithARefusedAttempt_althoughTheRunStaysInterrupted() {
        Workflow wf = workflow("wf-1", true);
        WorkflowRun run = interrupted("r1", wf, "RUNNING");
        stored(run);
        storedWorkflow(wf);
        interruptedRunsOf("wf-1", run);
        when(workflowExecutor.resumeFromStep("r1", TriggerSource.AUTO_RESUME))
                .thenThrow(new HardeningException(ErrorCode.UNATTENDED_REQUIRES_SEAL, "needs a seal"));

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));
        runScheduled();

        assertThat(run.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(pending.forWorkflow("wf-1")).isFalse();
    }

    @Test
    void pending_notForRunsTheResumerWillNotTouch() {
        Workflow plain = workflow("wf-plain", false);
        interruptedRunsOf("wf-plain", interrupted("r1", plain, "RUNNING"));
        storedWorkflow(plain);
        assertThat(pending.forWorkflow("wf-plain")).as("no opt-in").isFalse();

        Workflow optedIn = workflow("wf-paused", true);
        interruptedRunsOf("wf-paused", interrupted("r2", optedIn, "PAUSED"));
        assertThat(pending.forWorkflow("wf-paused")).as("a person paused it").isFalse();

        interruptedRunsOf("wf-none");
        assertThat(pending.forWorkflow("wf-none")).as("nothing interrupted").isFalse();
    }

    @Test
    void pending_notWhenAutoResumeWasSwitchedOffAtTheInterruption() {
        config.setEnabled(false);
        Workflow wf = workflow("wf-1", true);
        interruptedRunsOf("wf-1", interrupted("r1", wf, "RUNNING"));

        resumer.onRunsInterrupted(new RunInterruption.Event(List.of("r1"), RunInterruption.Cause.RESTART));

        assertThat(pending.forWorkflow("wf-1")).isFalse();
    }
}
