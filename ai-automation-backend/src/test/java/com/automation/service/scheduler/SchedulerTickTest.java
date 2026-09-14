package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerTickTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-11T10:00:00Z");

    @Mock private ScheduledWorkflowRepository scheduledRepository;
    @Mock private ScheduledWorkflowExecutor scheduledExecutor;
    @Mock private ActivityLogService activityLogService;
    @Mock private SchedulerConfig schedulerConfig;
    @Mock private SchedulerMaintenanceMode maintenanceMode;
    @Mock private SuspendState suspendState;
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    private SchedulerTick tick;
    private CatchUpQueue catchUpQueue;

    private Workflow testWorkflow;
    private ScheduledWorkflow testScheduled;

    @BeforeEach
    void setUp() {
        catchUpQueue = new CatchUpQueue(activityLogService);
        tick = new SchedulerTick(scheduledRepository, scheduledExecutor, activityLogService, schedulerConfig,
                maintenanceMode, suspendState,
                new ScheduleSkipPolicy(schedulerConfig, scheduledRepository, activityLogService),
                catchUpQueue, clock);

        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .build();

        testScheduled = ScheduledWorkflow.builder()
                .id("sched-1")
                .workflow(testWorkflow)
                .cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .description("Hourly run")
                .inputContext(Map.of("key", "value"))
                .build();
    }

    @Test
    void maintenanceMode_skipsTickCompletely() {
        when(maintenanceMode.isEnabled()).thenReturn(true);

        tick.checkAndExecuteScheduled();

        verifyNoInteractions(scheduledRepository, scheduledExecutor);
    }

    @Test
    void checkAndExecuteScheduled_dueSchedule_delegatesToExecutor() {
        testScheduled.setNextRunAt(Instant.now().minusSeconds(60));
        when(scheduledRepository.findDueSchedules(any(Instant.class))).thenReturn(List.of(testScheduled));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
    }

    @Test
    void checkAndExecuteScheduled_noDueSchedules_doesNothing() {
        when(scheduledRepository.findDueSchedules(any(Instant.class))).thenReturn(List.of());

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
    }

    @Test
    void checkAndExecuteScheduled_executionFails_continuesWithOthers() {
        ScheduledWorkflow sched1 = ScheduledWorkflow.builder()
                .id("s1").workflow(testWorkflow).cronExpression("0 0 * * * *")
                .enabled(true).nextRunAt(Instant.now().minusSeconds(60)).build();
        ScheduledWorkflow sched2 = ScheduledWorkflow.builder()
                .id("s2").workflow(testWorkflow).cronExpression("0 0 * * * *")
                .enabled(true).nextRunAt(Instant.now().minusSeconds(60)).build();

        when(scheduledRepository.findDueSchedules(any(Instant.class))).thenReturn(List.of(sched1, sched2));
        doThrow(new RuntimeException("fail")).when(scheduledExecutor).executeSingleSchedule("s1");

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("s1");
        verify(scheduledExecutor).executeSingleSchedule("s2");
    }

    @Test
    void checkAndExecuteScheduled_skipUntilFuture_skipsCronAndLogsActivity() {
        Instant dueAt = FIXED_NOW.minusSeconds(60);
        Instant skipUntil = FIXED_NOW.plus(3, ChronoUnit.HOURS);
        testScheduled.setNextRunAt(dueAt);

        when(schedulerConfig.getSkipUntil()).thenReturn(skipUntil.toString());
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        assertThat(testScheduled.isEnabled()).isTrue();
        assertThat(testScheduled.getNextRunAt()).isNotNull().isAfterOrEqualTo(skipUntil);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                eq("Schedule skipped: Test Workflow"),
                eq("SchedulerService"),
                eq("sched-1"),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue())
                .containsEntry("reason", "scheduler-skip-until")
                .containsEntry("skipUntil", skipUntil.toString())
                .containsEntry("previousNextRunAt", dueAt.toString());
    }

    @Test
    void checkAndExecuteScheduled_skipUntilFuture_skipsAllDueSchedules() {
        Instant dueAt = FIXED_NOW.minusSeconds(60);
        Instant skipUntil = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow sched1 = ScheduledWorkflow.builder()
                .id("s1").workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true).nextRunAt(dueAt).build();
        ScheduledWorkflow sched2 = ScheduledWorkflow.builder()
                .id("s2").workflow(testWorkflow).cronExpression("0 30 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true).nextRunAt(dueAt).build();

        when(schedulerConfig.getSkipUntil()).thenReturn(skipUntil.toString());
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(sched1, sched2));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        verify(scheduledRepository, times(2)).save(any(ScheduledWorkflow.class));
        verify(activityLogService, times(2)).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                startsWith("Schedule skipped:"),
                eq("SchedulerService"),
                any(),
                any()
        );
    }

    @Test
    void checkAndExecuteScheduled_skipUntilExpired_executesDueSchedule() {
        testScheduled.setNextRunAt(FIXED_NOW.minusSeconds(60));

        when(schedulerConfig.getSkipUntil()).thenReturn(FIXED_NOW.minusSeconds(1).toString());
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(scheduledRepository, never()).save(any());
        verify(activityLogService, never()).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                startsWith("Schedule skipped:"),
                any(),
                any(),
                any()
        );
    }

    @Test
    void checkAndExecuteScheduled_maintenanceMode_doesNotApplySkipUntil() {
        when(maintenanceMode.isEnabled()).thenReturn(true);

        tick.checkAndExecuteScheduled();

        verify(schedulerConfig, never()).getSkipUntil();
        verify(scheduledRepository, never()).findDueSchedules(any());
        verify(scheduledExecutor, never()).executeSingleSchedule(any());
    }

    @Test
    void checkAndExecuteScheduled_skipUntilFuture_disablesOnceSchedule() {
        Instant dueAt = FIXED_NOW.minusSeconds(60);
        Instant skipUntil = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(dueAt)
                .enabled(true)
                .nextRunAt(dueAt)
                .build();

        when(schedulerConfig.getSkipUntil()).thenReturn(skipUntil.toString());
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(onceSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        assertThat(onceSchedule.isEnabled()).isFalse();
        assertThat(onceSchedule.getNextRunAt()).isNull();
        verify(activityLogService).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                eq("Schedule skipped: Test Workflow"),
                eq("SchedulerService"),
                eq("sched-once"),
                any()
        );
    }

    @Test
    void checkAndExecuteScheduled_invalidSkipUntil_runsSchedulesNormallyAndWarnsOnce() {
        testScheduled.setNextRunAt(FIXED_NOW.minusSeconds(60));

        // Unparseable value: must be treated as "no skip" (fail open), not halt the scheduler.
        when(schedulerConfig.getSkipUntil()).thenReturn("not-a-timestamp");
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        // Two ticks with the same bad value.
        tick.checkAndExecuteScheduled();
        tick.checkAndExecuteScheduled();

        // Fail open: due schedules ran normally on both ticks, nothing was skipped/saved.
        verify(scheduledExecutor, times(2)).executeSingleSchedule("sched-1");
        verify(scheduledRepository, never()).save(any());

        // Dedup: warned exactly once for the same invalid value, no per-tick spam.
        verify(activityLogService, times(1)).warn(
                eq(LogCategory.SCHEDULE),
                contains("Invalid scheduler skip-until value"),
                eq("SchedulerService")
        );
        verify(activityLogService, never()).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                startsWith("Schedule skipped:"),
                any(),
                any(),
                any()
        );
    }

    @Test
    void checkAndExecuteScheduled_resumeAfterSuspend_skipsStaleCronAndRecordsResume() {
        // Previous tick 3 days ago → wall-clock gap >> grace → treated as a system resume.
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        // Stale schedule is NOT fired; it is advanced to its next future occurrence.
        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
        // Advanced from the 3-days-stale value forward to its next occurrence at/after now.
        assertThat(testScheduled.getNextRunAt()).isNotNull().isAfterOrEqualTo(FIXED_NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(
                eq(LogLevel.INFO),
                eq(LogCategory.SCHEDULE),
                eq("Schedule skipped (system suspend/resume): Test Workflow"),
                eq("SchedulerService"),
                eq("sched-1"),
                metadataCaptor.capture()
        );
        assertThat(metadataCaptor.getValue())
                .containsEntry("reason", "suspend-resume")
                .containsKey("suspendGapMinutes");
    }

    @Test
    void checkAndExecuteScheduled_resumeAfterSuspend_catchUpSchedule_executesOnce() {
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(true);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        tick.checkAndExecuteScheduled();

        // Opt-in catch-up: the missed run is executed exactly once, not skipped.
        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void checkAndExecuteScheduled_resumeAfterSuspend_globalCatchUpEnabled_executesScheduleWithPerScheduleCatchUpFalse() {
        // Global catch-up=true overrides per-schedule catchUpOnResume=false → should EXECUTE, not skip.
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(schedulerConfig.isCatchUpOnResume()).thenReturn(true);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(scheduledRepository, never()).save(any());
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
    }

    @Test
    void checkAndExecuteScheduled_normalTickWithinGrace_executesNormallyWithoutResume() {
        // Previous tick 60s ago, grace 5min → ordinary tick, not a resume.
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minusSeconds(60));
        testScheduled.setNextRunAt(FIXED_NOW.minusSeconds(30));

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(suspendState, never()).recordResume(any(), anyLong());
    }

    // ---- Cold start (first tick of the JVM) and sequential catch-up ------------------------------

    private ScheduledWorkflow cronSchedule(String id, Instant nextRunAt, boolean catchUp) {
        return ScheduledWorkflow.builder()
                .id(id).workflow(testWorkflow).cronExpression("0 0 * * * *")
                .scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(nextRunAt).catchUpOnResume(catchUp).build();
    }

    @Test
    void coldStart_missedScheduleWithoutFlag_isSkippedForwardNotFired() {
        // First tick: no previous tick; backend came up 10 minutes ago; occurrence was due 1h ago.
        ReflectionTestUtils.setField(tick, "startedAt", FIXED_NOW.minus(10, ChronoUnit.MINUTES));
        ScheduledWorkflow missed = cronSchedule("missed", FIXED_NOW.minus(1, ChronoUnit.HOURS), false);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(missed));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        assertThat(missed.getNextRunAt()).isAfterOrEqualTo(FIXED_NOW);
        verify(suspendState, never()).recordResume(any(), anyLong());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule skipped (backend was stopped): Test Workflow"), eq("SchedulerService"),
                eq("missed"), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("reason", "cold-start").containsKey("backendStartedAt");
        verify(activityLogService).warn(eq(LogCategory.SCHEDULE),
                contains("Cold start detected"), eq("SchedulerService"));
    }

    @Test
    void coldStart_occurrenceDueAfterStart_firesNormally() {
        ReflectionTestUtils.setField(tick, "startedAt", FIXED_NOW.minus(10, ChronoUnit.MINUTES));
        ScheduledWorkflow regular = cronSchedule("regular", FIXED_NOW.minusSeconds(30), false);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(regular));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("regular");
        verify(scheduledRepository, never()).save(any());
        verify(activityLogService, never()).warn(any(), contains("Cold start"), any());
    }

    @Test
    void coldStart_flaggedSchedules_areCaughtUpOneAfterAnother() {
        ReflectionTestUtils.setField(tick, "startedAt", FIXED_NOW.minus(10, ChronoUnit.MINUTES));
        ScheduledWorkflow first = cronSchedule("first", FIXED_NOW.minus(3, ChronoUnit.HOURS), true);
        ScheduledWorkflow second = cronSchedule("second", FIXED_NOW.minus(2, ChronoUnit.HOURS), true);
        var runFirst = new ScheduledWorkflowExecutor.ScheduledRunRef("run-first", ScheduledWorkflow.TargetType.WORKFLOW);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        // Listed in reverse order on purpose: the queue must sort by original due time.
        when(scheduledRepository.findDueSchedules(any())).thenReturn(List.of(second, first));
        when(scheduledExecutor.executeSingleSchedule("first")).thenReturn(java.util.Optional.of(runFirst));

        // Tick 1 (cold start): only the earliest missed schedule starts.
        tick.checkAndExecuteScheduled();
        verify(scheduledExecutor).executeSingleSchedule("first");
        verify(scheduledExecutor, never()).executeSingleSchedule("second");
        verify(scheduledRepository, never()).save(any());
        assertThat(catchUpQueue.snapshot().active().scheduleId()).isEqualTo("first");
        assertThat(catchUpQueue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId).containsExactly("second");

        // Tick 2 (normal): first still running → second waits, and the regular path must not fire it
        // even though the repository still lists it as due.
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minusSeconds(60));
        when(scheduledExecutor.isFinished(runFirst)).thenReturn(false);
        tick.checkAndExecuteScheduled();
        verify(scheduledExecutor, never()).executeSingleSchedule("second");

        // Tick 3: first finished → second starts.
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minusSeconds(60));
        when(scheduledExecutor.isFinished(runFirst)).thenReturn(true);
        when(scheduledExecutor.executeSingleSchedule("second"))
                .thenReturn(java.util.Optional.of(new ScheduledWorkflowExecutor.ScheduledRunRef("run-second", ScheduledWorkflow.TargetType.WORKFLOW)));
        tick.checkAndExecuteScheduled();
        verify(scheduledExecutor).executeSingleSchedule("second");
        assertThat(catchUpQueue.snapshot().pending()).isEmpty();
        verify(activityLogService).warn(eq(LogCategory.SCHEDULE),
                contains("2 missed schedule(s): 0 skipped forward, 2 queued"), eq("SchedulerService"));
    }

    @Test
    void coldStart_duringSkipUntilPause_skipsEvenFlaggedSchedules() {
        ReflectionTestUtils.setField(tick, "startedAt", FIXED_NOW.minus(10, ChronoUnit.MINUTES));
        Instant skipUntil = FIXED_NOW.plus(2, ChronoUnit.HOURS);
        ScheduledWorkflow flagged = cronSchedule("flagged", FIXED_NOW.minus(1, ChronoUnit.HOURS), true);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(schedulerConfig.getSkipUntil()).thenReturn(skipUntil.toString());
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(flagged));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor, never()).executeSingleSchedule(any());
        assertThat(catchUpQueue.isEmpty()).isTrue();
        assertThat(flagged.getNextRunAt()).isAfterOrEqualTo(skipUntil);
    }

    @Test
    void coldStart_resumeDetectionDisabled_firesEverythingDue() {
        // resume-grace-minutes = 0 disables both resume and cold-start handling (legacy behaviour).
        ScheduledWorkflow missed = cronSchedule("missed", FIXED_NOW.minus(1, ChronoUnit.HOURS), false);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(0L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(missed));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("missed");
    }

    @Test
    void resumeAfterSuspend_flaggedSchedules_goThroughTheSameSequentialQueue() {
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.HOURS));
        ScheduledWorkflow a = cronSchedule("a", FIXED_NOW.minus(2, ChronoUnit.HOURS), true);
        ScheduledWorkflow b = cronSchedule("b", FIXED_NOW.minus(1, ChronoUnit.HOURS), true);
        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(a, b));
        when(scheduledExecutor.executeSingleSchedule("a"))
                .thenReturn(java.util.Optional.of(new ScheduledWorkflowExecutor.ScheduledRunRef("run-a", ScheduledWorkflow.TargetType.WORKFLOW)));

        tick.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("a");
        verify(scheduledExecutor, never()).executeSingleSchedule("b");
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
        verify(activityLogService).warn(eq(LogCategory.SCHEDULE),
                contains("System resume detected (paused ~180 min) — 2 missed schedule(s): 0 skipped forward, 2 queued"),
                eq("SchedulerService"));
    }

    @Test
    void checkAndExecuteScheduled_resumeAfterSuspend_warnsActivityLog() {
        ReflectionTestUtils.setField(tick, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        tick.checkAndExecuteScheduled();

        verify(activityLogService).warn(
                eq(LogCategory.SCHEDULE),
                contains("System resume detected"),
                eq("SchedulerService")
        );
    }
}
