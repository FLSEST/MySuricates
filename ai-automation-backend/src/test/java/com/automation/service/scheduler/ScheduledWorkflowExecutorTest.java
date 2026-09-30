package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.workflow.PendingAutoResumes;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledWorkflowExecutorTest {

    @Mock
    private ScheduledWorkflowRepository scheduledRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ChainRunRepository chainRunRepository;

    @Mock
    private WorkflowExecutor workflowExecutor;

    @Mock
    private ChainExecutor chainExecutor;

    @Mock
    private ActivityLogService activityLogService;

    // Real system-zone clock: the executor now reads time via the injected Clock, and these tests
    // build nextRunAt/endDate relative to real Instant.now(), so the clock must track real time.
    @Spy
    private Clock clock = Clock.systemDefaultZone();

    @Spy
    private ModuleRegistry modules = ModuleRegistry.allEnabled();

    @Mock
    private PendingAutoResumes pendingAutoResumes;

    private ScheduledWorkflowExecutor executor;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        executor = executorWith(clock, modules);
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .activatedSkills(new ArrayList<>())
                .build();
    }

    @Test
    void executeSingleSchedule_cronSchedule_setsNextRun() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-cron")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of())
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-cron")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-cron")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(false);

        executor.executeSingleSchedule("sched-cron");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), any(String.class), isNull(), eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getLastRunAt()).isNotNull();
        assertThat(scheduled.getNextRunAt()).isNotNull();
        assertThat(scheduled.isEnabled()).isTrue();
    }

    @Test
    void executeSingleSchedule_onceSchedule_disablesAfterExecution() {
        Instant scheduledAt = Instant.now().minusSeconds(60);
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-once")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(scheduledAt)
                .enabled(true)
                .nextRunAt(scheduledAt)
                .inputContext(Map.of())
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-once")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-once")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(false);

        executor.executeSingleSchedule("sched-once");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), any(String.class), isNull(), eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getLastRunAt()).isNotNull();
        assertThat(scheduled.getNextRunAt()).isNull();
        assertThat(scheduled.isEnabled()).isFalse();
    }

    @Test
    void executeSingleSchedule_notDueYet_doesNotExecute() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-future")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().plusSeconds(3600))
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-future")).thenReturn(Optional.of(scheduled));

        executor.executeSingleSchedule("sched-future");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.SCHEDULE));
    }

    @Test
    void executeSingleSchedule_disabledSchedule_doesNotExecute() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-disabled")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(false)
                .nextRunAt(Instant.now().minusSeconds(60))
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-disabled")).thenReturn(Optional.of(scheduled));

        executor.executeSingleSchedule("sched-disabled");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.SCHEDULE));
    }

    @Test
    void executeSingleSchedule_nonExistent_doesNothing() {
        when(scheduledRepository.findByIdWithWorkflowSkills("unknown")).thenReturn(Optional.empty());

        executor.executeSingleSchedule("unknown");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.SCHEDULE));
    }

    @Test
    void executeSingleSchedule_alreadyRunning_skipsExecution() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-ghost")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of())
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-ghost")).thenReturn(Optional.of(scheduled));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-ghost");

        verify(workflowExecutor, never()).execute(any(), any(), eq(TriggerSource.SCHEDULE));
    }

    @Test
    void executeSingleSchedule_chainAlreadyRunning_skipsExecution() {
        WorkflowChain chain = WorkflowChain.builder()
                .id("chain-1")
                .name("Test Chain")
                .build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-chain-ghost")
                .chain(chain)
                .targetType(TargetType.CHAIN)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of())
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-chain-ghost")).thenReturn(Optional.of(scheduled));
        when(chainRunRepository.existsByChainIdAndStatusIn(eq("chain-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-chain-ghost");

        verify(chainExecutor, never()).execute(any(), any());
    }

    @Test
    void executeSingleSchedule_chainTargetWithChainsModuleDisabled_skipsButAdvancesSchedule() {
        var coreOnly = executorWith(clock, ModuleRegistry.of(Map.of(), Map.of()));
        WorkflowChain chain = WorkflowChain.builder().id("chain-1").name("Nightly Chain").build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-chain-off")
                .chain(chain)
                .targetType(TargetType.CHAIN)
                .conflictBehavior(ScheduledWorkflow.ConflictBehavior.OVERLAP)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of())
                .build();
        when(scheduledRepository.findByIdWithWorkflowSkills("sched-chain-off")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-chain-off")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(coreOnly.executeSingleSchedule("sched-chain-off")).isEmpty();

        verifyNoInteractions(chainExecutor);
        verify(scheduledRepository).save(argThat(sw -> ((ScheduledWorkflow) sw).getLastRunAt() != null));
    }

    @Test
    void executeSingleSchedule_executeThrows_nextRunAtStillUpdated() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-throws")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of())
                .build();

        when(scheduledRepository.findByIdWithWorkflowSkills("sched-throws")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-throws")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(false);
        doThrow(new RuntimeException("LazyInitializationException: no Session"))
                .when(workflowExecutor).execute(any(), any(), eq(TriggerSource.SCHEDULE));

        // Should not throw out of executeSingleSchedule — error is caught by checkAndExecuteScheduled
        try {
            executor.executeSingleSchedule("sched-throws");
        } catch (RuntimeException ignored) {
            // expected: exception re-thrown after finally
        }

        // nextRunAt must have been updated despite the exception (try-finally)
        verify(scheduledRepository).save(argThat(sw ->
                ((ScheduledWorkflow) sw).getLastRunAt() != null));
    }

    @Test
    void should_disableSchedule_when_cronMaxRunsReached() {
        ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                .id("sched-cron-max")
                .scheduleType(ScheduleType.CRON)
                .targetType(TargetType.WORKFLOW)
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .runCount(4)
                .maxRuns(5)
                .enabled(true)
                .build();
        when(scheduledRepository.findById("sched-cron-max")).thenReturn(Optional.of(schedule));

        executor.updateScheduleAfterRun("sched-cron-max", ScheduleType.CRON, "0 0 * * * *");

        assertThat(schedule.getRunCount()).isEqualTo(5);
        assertThat(schedule.isEnabled()).isFalse();
        assertThat(schedule.getNextRunAt()).isNull();
        assertThat(schedule.getLastRunAt()).isNotNull();
        verify(scheduledRepository).save(schedule);
    }

    @Test
    void should_disableSchedule_when_cronEndDateExceeded() {
        ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                .id("sched-cron-end")
                .scheduleType(ScheduleType.CRON)
                .targetType(TargetType.WORKFLOW)
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .runCount(2)
                .maxRuns(0)
                .endDate(Instant.now().minusSeconds(3600))
                .enabled(true)
                .build();
        when(scheduledRepository.findById("sched-cron-end")).thenReturn(Optional.of(schedule));

        executor.updateScheduleAfterRun("sched-cron-end", ScheduleType.CRON, "0 0 * * * *");

        assertThat(schedule.getRunCount()).isEqualTo(3);
        assertThat(schedule.isEnabled()).isFalse();
        assertThat(schedule.getNextRunAt()).isNull();
        assertThat(schedule.getLastRunAt()).isNotNull();
        verify(scheduledRepository).save(schedule);
    }

    @Test
    void should_disableSchedule_when_delayedScheduleExecuted() {
        ScheduledWorkflow schedule = ScheduledWorkflow.builder()
                .id("sched-delayed")
                .scheduleType(ScheduleType.DELAYED)
                .targetType(TargetType.WORKFLOW)
                .workflow(testWorkflow)
                .enabled(true)
                .build();
        when(scheduledRepository.findById("sched-delayed")).thenReturn(Optional.of(schedule));

        executor.updateScheduleAfterRun("sched-delayed", ScheduleType.DELAYED, null);

        assertThat(schedule.isEnabled()).isFalse();
        assertThat(schedule.getNextRunAt()).isNull();
        assertThat(schedule.getLastRunAt()).isNotNull();
        verify(scheduledRepository).save(schedule);
    }

    @Test
    void should_doNothing_when_updateScheduleAfterRun_scheduleNotFound() {
        when(scheduledRepository.findById("missing")).thenReturn(Optional.empty());

        executor.updateScheduleAfterRun("missing", ScheduleType.CRON, "0 0 * * * *");

        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void executeSingleSchedule_returnsRunReference_forWorkflow() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-ref").workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60)).inputContext(Map.of()).build();
        when(scheduledRepository.findByIdWithWorkflowSkills("sched-ref")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-ref")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(false);
        when(workflowExecutor.execute(eq(testWorkflow), any(), eq("sched-ref"), isNull(), eq(TriggerSource.SCHEDULE)))
                .thenReturn(com.automation.entity.WorkflowRun.builder().id("run-42").build());

        Optional<ScheduledWorkflowExecutor.ScheduledRunRef> ref = executor.executeSingleSchedule("sched-ref");

        assertThat(ref).isPresent();
        assertThat(ref.get().runId()).isEqualTo("run-42");
        assertThat(ref.get().targetType()).isEqualTo(TargetType.WORKFLOW);
    }

    @Test
    void executeSingleSchedule_returnsEmpty_whenGhostRunProtectionSkips() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-busy").workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60)).inputContext(Map.of()).build();
        when(scheduledRepository.findByIdWithWorkflowSkills("sched-busy")).thenReturn(Optional.of(scheduled));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        assertThat(executor.executeSingleSchedule("sched-busy")).isEmpty();
        verify(workflowExecutor, never()).execute(any(), any(), any(), any(), eq(TriggerSource.SCHEDULE));
    }

    // --- SKIP drops the occurrence instead of retrying every minute (TODO ade3fef8) ---

    private ScheduledWorkflow dueCron(String id) {
        return ScheduledWorkflow.builder()
                .id(id).workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60)).inputContext(Map.of()).build();
    }

    private void stubScheduleLookups(ScheduledWorkflow scheduled) {
        when(scheduledRepository.findByIdWithWorkflowSkills(scheduled.getId())).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void skipWhileWorkflowRunning_movesNextRunAtPastNow_withoutCountingARun() {
        ScheduledWorkflow scheduled = dueCron("sched-skip");
        Instant skippedAt = scheduled.getNextRunAt();
        stubScheduleLookups(scheduled);
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        assertThat(executor.executeSingleSchedule("sched-skip")).isEmpty();

        verify(workflowExecutor, never()).execute(any(), any(), any(), any(), eq(TriggerSource.SCHEDULE));
        // The dropped occurrence is gone: the next tick finds nothing due and does not retry.
        assertThat(scheduled.getNextRunAt()).isAfter(Instant.now());
        assertThat(scheduled.isEnabled()).isTrue();
        // Nothing ran: no last run, and the skip does not use up maxRuns.
        assertThat(scheduled.getLastRunAt()).isNull();
        assertThat(scheduled.getRunCount()).isZero();

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule skipped (previous run still in progress): Test Workflow"),
                eq("ScheduledWorkflowExecutor"), eq("sched-skip"), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("reason", "target-busy")
                .containsEntry("previousNextRunAt", skippedAt.toString())
                .containsEntry("nextRunAt", scheduled.getNextRunAt().toString());
        verify(activityLogService, never()).info(eq(LogCategory.SCHEDULE), startsWith("Schedule triggered"),
                any(), any());
    }

    @Test
    void busyStates_arePausedAndRunning() {
        // A paused run resumes later — a second run next to it is the overlap SKIP avoids.
        assertThat(ScheduleConflictCheck.BUSY_STATES)
                .containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.PAUSED);
    }

    @Test
    void skipWhileChainBusy_movesNextRunAtPastNow() {
        WorkflowChain chain = WorkflowChain.builder().id("chain-1").name("Nightly Chain").build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-chain-skip").chain(chain).targetType(TargetType.CHAIN)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60)).inputContext(Map.of()).build();
        stubScheduleLookups(scheduled);
        when(chainRunRepository.existsByChainIdAndStatusIn(eq("chain-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-chain-skip");

        verifyNoInteractions(chainExecutor);
        assertThat(scheduled.getNextRunAt()).isAfter(Instant.now());
        assertThat(scheduled.getLastRunAt()).isNull();
    }

    @Test
    void skipOnceScheduleWhileTargetBusy_dropsItsSingleOccurrence() {
        Instant scheduledAt = Instant.now().minusSeconds(60);
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-once-skip").workflow(testWorkflow).cronExpression("")
                .scheduleType(ScheduleType.ONCE).scheduledAt(scheduledAt).enabled(true)
                .nextRunAt(scheduledAt).inputContext(Map.of()).build();
        stubScheduleLookups(scheduled);
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-once-skip");

        // Like a one-off inside the skip-until window: the single firing is dropped, not deferred.
        verify(workflowExecutor, never()).execute(any(), any(), any(), any(), eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.isEnabled()).isFalse();
        assertThat(scheduled.getNextRunAt()).isNull();
        assertThat(scheduled.getLastRunAt()).isNull();
    }

    @Test
    void skip_leavesTheRunCounterAlone_soMaxRunsCountsStartedRunsOnly() {
        ScheduledWorkflow scheduled = dueCron("sched-skip-max");
        scheduled.setMaxRuns(5);
        scheduled.setRunCount(4);
        stubScheduleLookups(scheduled);
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-skip-max");

        assertThat(scheduled.getRunCount()).isEqualTo(4);
        assertThat(scheduled.isEnabled()).isTrue();
        assertThat(scheduled.getNextRunAt()).isAfter(Instant.now());
    }

    @Test
    void skipAfterEndDate_disablesTheSchedule_likeAfterARun() {
        ScheduledWorkflow scheduled = dueCron("sched-skip-end");
        // The skipped occurrence (60 s ago) still lies before the end date, the skip after it. An
        // occurrence behind the end date never gets this far (TODO 9a3e6c91, tests below).
        scheduled.setEndDate(scheduled.getNextRunAt().plusSeconds(30));
        stubScheduleLookups(scheduled);
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES))).thenReturn(true);

        executor.executeSingleSchedule("sched-skip-end");

        assertThat(scheduled.isEnabled()).isFalse();
        assertThat(scheduled.getNextRunAt()).isNull();
    }

    @Test
    void overlapSchedule_startsNextToABusyTarget() {
        ScheduledWorkflow scheduled = dueCron("sched-overlap");
        scheduled.setConflictBehavior(ScheduledWorkflow.ConflictBehavior.OVERLAP);
        stubScheduleLookups(scheduled);

        executor.executeSingleSchedule("sched-overlap");

        verifyNoInteractions(workflowRunRepository);
        verifyNoInteractions(pendingAutoResumes);
        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), eq("sched-overlap"), isNull(),
                eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getRunCount()).isEqualTo(1);
    }

    // --- A pending automatic resume counts as a busy target (TODO 070ccc3d) ---

    @Test
    void skipWhileAnAutoResumeIsPending_dropsTheOccurrence_withItsOwnReason() {
        ScheduledWorkflow scheduled = dueCron("sched-resume");
        Instant skippedAt = scheduled.getNextRunAt();
        stubScheduleLookups(scheduled);
        when(pendingAutoResumes.forWorkflow("wf-1")).thenReturn(true);

        assertThat(executor.executeSingleSchedule("sched-resume")).isEmpty();

        verify(workflowExecutor, never()).execute(any(), any(), any(), any(), eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getNextRunAt()).isAfter(Instant.now());
        assertThat(scheduled.getLastRunAt()).isNull();
        assertThat(scheduled.getRunCount()).isZero();
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule skipped (interrupted run is being resumed automatically): Test Workflow"),
                eq("ScheduledWorkflowExecutor"), eq("sched-resume"), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("reason", "auto-resume-pending")
                .containsEntry("previousNextRunAt", skippedAt.toString());
    }

    /**
     * The pending resume is asked before the RUNNING/PAUSED check: the resumed run turns RUNNING and
     * only then stops counting as pending, so in this order a check never falls between the two.
     */
    @Test
    void pendingResume_isCheckedBeforeTheRunningRuns() {
        ScheduledWorkflow scheduled = dueCron("sched-order");
        stubScheduleLookups(scheduled);
        targetBusy(false);

        executor.executeSingleSchedule("sched-order");

        var order = inOrder(pendingAutoResumes, workflowRunRepository);
        order.verify(pendingAutoResumes).forWorkflow("wf-1");
        order.verify(workflowRunRepository).existsByWorkflowIdAndStatusIn("wf-1", ScheduleConflictCheck.BUSY_STATES);
        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), eq("sched-order"), isNull(),
                eq(TriggerSource.SCHEDULE));
    }

    // --- The end date bounds the occurrences, not only the moment of the check (TODO 9a3e6c91) ---

    /** A full local hour in mid-June (no DST change in any zone): an hourly cron's next run is 11:00. */
    private static final Instant TEN_O_CLOCK =
            LocalDateTime.of(2026, 6, 15, 10, 0).atZone(ZoneId.systemDefault()).toInstant();

    private ScheduledWorkflowExecutor executorAt(Instant now) {
        return executorWith(Clock.fixed(now, ZoneId.systemDefault()), modules);
    }

    private ScheduledWorkflowExecutor executorWith(Clock executorClock, ModuleRegistry moduleRegistry) {
        return new ScheduledWorkflowExecutor(scheduledRepository, workflowRunRepository, chainRunRepository,
                workflowExecutor, chainExecutor, activityLogService, executorClock, moduleRegistry,
                new ScheduleConflictCheck(workflowRunRepository, chainRunRepository, pendingAutoResumes));
    }

    private ScheduledWorkflow hourly(String id, Instant nextRunAt, Instant endDate) {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id(id).workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(nextRunAt).endDate(endDate).inputContext(Map.of()).build();
        stubScheduleLookups(scheduled);
        return scheduled;
    }

    private void targetBusy(boolean busy) {
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduleConflictCheck.BUSY_STATES)))
                .thenReturn(busy);
    }

    @Test
    void endDateBetweenTwoOccurrences_theRunBeforeItIsTheLastOne() {
        // Hourly, due at 10:00, ends at 10:30: 10:00 belongs to the schedule, 11:00 no longer does.
        ScheduledWorkflow scheduled = hourly("sched-end-run", TEN_O_CLOCK, TEN_O_CLOCK.plus(30, ChronoUnit.MINUTES));
        targetBusy(false);

        executorAt(TEN_O_CLOCK.plusSeconds(30)).executeSingleSchedule("sched-end-run");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), eq("sched-end-run"), isNull(),
                eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getRunCount()).isEqualTo(1);
        // Not 11:00 — that next run lay behind the end date and still started once.
        assertThat(scheduled.getNextRunAt()).isNull();
        assertThat(scheduled.isEnabled()).isFalse();
    }

    @Test
    void endDateBetweenTwoOccurrences_aSkippedOccurrenceEndsTheScheduleAsWell() {
        ScheduledWorkflow scheduled = hourly("sched-end-skip", TEN_O_CLOCK, TEN_O_CLOCK.plus(30, ChronoUnit.MINUTES));
        targetBusy(true);

        executorAt(TEN_O_CLOCK.plusSeconds(30)).executeSingleSchedule("sched-end-skip");

        verify(workflowExecutor, never()).execute(any(), any(), any(), any(), eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.getRunCount()).isZero();
        assertThat(scheduled.getNextRunAt()).isNull();
        assertThat(scheduled.isEnabled()).isFalse();
    }

    @Test
    void storedOccurrenceBehindTheEndDate_neverStarts_andEndsTheSchedule() {
        // Stored before the fix, or the end date was moved forward by an update: due 11:00, ends 10:30.
        Instant elevenOClock = TEN_O_CLOCK.plus(1, ChronoUnit.HOURS);
        Instant endDate = TEN_O_CLOCK.plus(30, ChronoUnit.MINUTES);
        ScheduledWorkflow scheduled = hourly("sched-end-stored", elevenOClock, endDate);

        assertThat(executorAt(elevenOClock.plusSeconds(30)).executeSingleSchedule("sched-end-stored")).isEmpty();

        verifyNoInteractions(workflowExecutor);
        assertThat(scheduled.isEnabled()).isFalse();
        assertThat(scheduled.getNextRunAt()).isNull();
        assertThat(scheduled.getLastRunAt()).isNull();
        assertThat(scheduled.getRunCount()).isZero();
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule ended (end date reached): Test Workflow"),
                eq("ScheduledWorkflowExecutor"), eq("sched-end-stored"), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("reason", "end-date")
                .containsEntry("previousNextRunAt", elevenOClock.toString())
                .containsEntry("endDate", endDate.toString());
    }

    @Test
    void occurrenceExactlyAtTheEndDate_doesNotStart() {
        // "Disabled when now >= endDate": at the end date itself the schedule is over.
        ScheduledWorkflow scheduled = hourly("sched-end-exact", TEN_O_CLOCK, TEN_O_CLOCK);

        executorAt(TEN_O_CLOCK.plusSeconds(30)).executeSingleSchedule("sched-end-exact");

        verifyNoInteractions(workflowExecutor);
        assertThat(scheduled.isEnabled()).isFalse();
        assertThat(scheduled.getNextRunAt()).isNull();
    }

    @Test
    void lastOccurrenceBeforeTheEndDate_stillRuns_whenTheTickPicksItUpAfterTheEndDate() {
        // Due 10:00, ends 10:00:10, the 60-s tick comes at 10:00:30. The occurrence belongs to the
        // schedule; whether it runs must not depend on where the tick happens to fall.
        ScheduledWorkflow scheduled = hourly("sched-end-late", TEN_O_CLOCK, TEN_O_CLOCK.plusSeconds(10));
        targetBusy(false);

        executorAt(TEN_O_CLOCK.plusSeconds(30)).executeSingleSchedule("sched-end-late");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), eq("sched-end-late"), isNull(),
                eq(TriggerSource.SCHEDULE));
        assertThat(scheduled.isEnabled()).isFalse();
        assertThat(scheduled.getNextRunAt()).isNull();
    }

    @Test
    void isFinished_reflectsRunStatus() {
        var ref = new ScheduledWorkflowExecutor.ScheduledRunRef("run-1", TargetType.WORKFLOW);
        when(workflowRunRepository.findById("run-1"))
                .thenReturn(Optional.of(com.automation.entity.WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build()));
        assertThat(executor.isFinished(ref)).isFalse();

        when(workflowRunRepository.findById("run-1"))
                .thenReturn(Optional.of(com.automation.entity.WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build()));
        assertThat(executor.isFinished(ref)).isTrue();

        // Unknown run (deleted meanwhile) must not block the queue forever.
        when(workflowRunRepository.findById("run-1")).thenReturn(Optional.empty());
        assertThat(executor.isFinished(ref)).isTrue();

        var chainRef = new ScheduledWorkflowExecutor.ScheduledRunRef("chain-1", TargetType.CHAIN);
        when(chainRunRepository.findById("chain-1"))
                .thenReturn(Optional.of(com.automation.entity.ChainRun.builder().id("chain-1").status(RunStatus.PAUSED).build()));
        assertThat(executor.isFinished(chainRef)).isFalse();
        assertThat(executor.isFinished(null)).isTrue();
    }


    /**
     * Modul goals aus (TODO c60bf914): ein zielgebundener Zeitplan – der Ziel-Waechter traegt goal_id in
     * seinem Eingabekontext – startet keinen Lauf, der ohne Zieldaten scheitern wuerde, und rueckt weiter.
     */
    @Test
    void executeSingleSchedule_goalScheduleWhileGoalsIsOff_isSkippedAndMovesOn() {
        org.mockito.Mockito.doReturn(false).when(modules).isEnabled(com.automation.modules.ModuleCatalog.GOALS);
        Instant due = Instant.now().minusSeconds(60);
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-guard")
                .workflow(testWorkflow)
                .cronExpression("0 0 8 * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(due)
                .inputContext(Map.of("goal_id", "g1"))
                .build();
        when(scheduledRepository.findByIdWithWorkflowSkills("sched-guard")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-guard")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduledWorkflowExecutor.BUSY_STATES))).thenReturn(false);

        assertThat(executor.executeSingleSchedule("sched-guard")).isEmpty();

        verify(workflowExecutor, org.mockito.Mockito.never()).execute(any(), any(), any(String.class), any(), any());
        assertThat(scheduled.getNextRunAt()).as("naechster Termin statt Wiederholung jeden Tick").isAfter(due);
        assertThat(scheduled.isEnabled()).as("der Zeitplan bleibt, nach dem Wiedereinschalten laeuft er wieder").isTrue();
        verify(activityLogService, org.mockito.Mockito.never()).error(any(), any(), any(), any());
    }

    @Test
    void executeSingleSchedule_goalScheduleWhileGoalsIsOn_runs() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .id("sched-guard-on")
                .workflow(testWorkflow)
                .cronExpression("0 0 8 * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .nextRunAt(Instant.now().minusSeconds(60))
                .inputContext(Map.of("goal_id", "g1"))
                .build();
        when(scheduledRepository.findByIdWithWorkflowSkills("sched-guard-on")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.findById("sched-guard-on")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRunRepository.existsByWorkflowIdAndStatusIn(eq("wf-1"), eq(ScheduledWorkflowExecutor.BUSY_STATES))).thenReturn(false);

        executor.executeSingleSchedule("sched-guard-on");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of("goal_id", "g1")), any(String.class), isNull(),
                eq(TriggerSource.SCHEDULE));
    }
}
