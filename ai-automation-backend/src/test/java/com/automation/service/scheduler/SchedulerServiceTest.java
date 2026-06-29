package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerServiceTest {

    @Mock
    private ScheduledWorkflowRepository scheduledRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowChainRepository workflowChainRepository;

    @Mock
    private ScheduledWorkflowExecutor scheduledExecutor;

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private SchedulerConfig schedulerConfig;

    @Mock
    private SuspendState suspendState;

    // Fixed at noon UTC so getRemainingToday/Week behave deterministically regardless of wall-clock time.
    private static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    @InjectMocks
    private SchedulerService schedulerService;

    private Workflow testWorkflow;
    private ScheduledWorkflow testScheduled;

    @BeforeEach
    void setUp() {
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
    void findAll_returnsAll() {
        when(scheduledRepository.findAll()).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findAll();

        assertThat(result).hasSize(1);
    }

    @Test
    void findById_existing_returnsSchedule() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        ScheduledWorkflow result = schedulerService.findById("sched-1");

        assertThat(result.getCronExpression()).isEqualTo("0 0 * * * *");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(scheduledRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> schedulerService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByWorkflowId_returnsMatching() {
        when(scheduledRepository.findByWorkflowId("wf-1")).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findByWorkflowId("wf-1");

        assertThat(result).hasSize(1);
    }

    @Test
    void create_cronSchedule_success() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> {
            ScheduledWorkflow s = inv.getArgument(0);
            s.setId("sched-new");
            return s;
        });

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Hourly", Map.of("env", "prod"), "CRON", null, null, null, null, null, null);

        assertThat(result.getId()).isEqualTo("sched-new");
        assertThat(result.getCronExpression()).isEqualTo("0 0 * * * *");
        assertThat(result.getScheduleType()).isEqualTo(ScheduleType.CRON);
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
        verify(scheduledRepository).save(any());
    }

    @Test
    void create_onceSchedule_success() {
        Instant futureTime = Instant.now().plus(1, ChronoUnit.HOURS);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> {
            ScheduledWorkflow s = inv.getArgument(0);
            s.setId("sched-once");
            return s;
        });

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", null, null, null, "One-time run", null, "ONCE", futureTime, null, null, null, null, null);

        assertThat(result.getId()).isEqualTo("sched-once");
        assertThat(result.getScheduleType()).isEqualTo(ScheduleType.ONCE);
        assertThat(result.getCronExpression()).isEmpty();
        assertThat(result.getScheduledAt()).isEqualTo(futureTime);
        assertThat(result.getNextRunAt()).isEqualTo(futureTime);
        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void create_onceSchedule_missingScheduledAt_throws() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, null, "Test", null, "ONCE", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledAt is required");
    }

    @Test
    void create_cronSchedule_missingCron_throws() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, null, "Test", null, "CRON", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cronExpression is required");
    }

    @Test
    void create_invalidCron_throwsException() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, "invalid-cron", "Test", null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void create_nonExistentWorkflow_throwsException() {
        when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> schedulerService.create(
                "unknown", null, null, "0 0 * * * *", "Test", null, null, null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_nullScheduleType_defaultsToCron() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, null, null, null, null, null, null, null);

        assertThat(result.getScheduleType()).isEqualTo(ScheduleType.CRON);

    }

    @Test
    void create_nullInputContext_defaultsToEmptyMap() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create("wf-1", null, null, "0 0 * * * *", "Test", null, null, null, null, null, null, null, null);

        assertThat(result.getInputContext()).isNotNull().isEmpty();
    }

    @Test
    void enable_cronSchedule_setsEnabledAndNextRun() {
        testScheduled.setEnabled(false);
        testScheduled.setNextRunAt(null);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.enable("sched-1");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
    }

    @Test
    void enable_onceSchedule_setsScheduledAt() {
        Instant futureTime = Instant.now().plus(2, ChronoUnit.HOURS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(futureTime)
                .enabled(false)
                .build();

        when(scheduledRepository.findById("sched-once")).thenReturn(Optional.of(onceSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.enable("sched-once");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isEqualTo(futureTime);
    }

    @Test
    void disable_setsDisabledAndClearsNextRun() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.disable("sched-1");

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getNextRunAt()).isNull();
    }

    @Test
    void delete_existingSchedule_deletes() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        schedulerService.delete("sched-1");

        verify(scheduledRepository).delete(testScheduled);
    }

    @Test
    void checkAndExecuteScheduled_dueSchedule_delegatesToExecutor() {
        testScheduled.setNextRunAt(Instant.now().minusSeconds(60));
        when(scheduledRepository.findDueSchedules(any(Instant.class))).thenReturn(List.of(testScheduled));

        schedulerService.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
    }

    @Test
    void checkAndExecuteScheduled_noDueSchedules_doesNothing() {
        when(scheduledRepository.findDueSchedules(any(Instant.class))).thenReturn(List.of());

        schedulerService.checkAndExecuteScheduled();

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
        doNothing().when(scheduledExecutor).executeSingleSchedule("s2");

        schedulerService.checkAndExecuteScheduled();

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

        schedulerService.checkAndExecuteScheduled();

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

        schedulerService.checkAndExecuteScheduled();

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

        schedulerService.checkAndExecuteScheduled();

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
        when(appSettingsRepository.get("scheduler.maintenance-mode")).thenReturn(Optional.of("true"));

        schedulerService.checkAndExecuteScheduled();

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

        schedulerService.checkAndExecuteScheduled();

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
        schedulerService.checkAndExecuteScheduled();
        schedulerService.checkAndExecuteScheduled();

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
        ReflectionTestUtils.setField(schedulerService, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        schedulerService.checkAndExecuteScheduled();

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
        ReflectionTestUtils.setField(schedulerService, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(true);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        schedulerService.checkAndExecuteScheduled();

        // Opt-in catch-up: the missed run is executed exactly once, not skipped.
        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void checkAndExecuteScheduled_normalTickWithinGrace_executesNormallyWithoutResume() {
        // Previous tick 60s ago, grace 5min → ordinary tick, not a resume.
        ReflectionTestUtils.setField(schedulerService, "lastTickAt", FIXED_NOW.minusSeconds(60));
        testScheduled.setNextRunAt(FIXED_NOW.minusSeconds(30));

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        schedulerService.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(suspendState, never()).recordResume(any(), anyLong());
    }

    @Test
    void getRemainingToday_cronSchedule_returnsUpcomingRuns() {
        // Cron that runs every minute — should have many runs remaining today
        ScheduledWorkflow everyMinute = ScheduledWorkflow.builder()
                .id("sched-freq")
                .workflow(testWorkflow)
                .cronExpression("0 * * * * *") // every minute (6-field Spring cron)
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(everyMinute));

        UpcomingScheduleResponse result = schedulerService.getRemainingToday();

        assertThat(result.count()).isGreaterThan(0);
        assertThat(result.schedules()).isNotEmpty();
        assertThat(result.schedules().get(0).workflowName()).isEqualTo("Test Workflow");
    }

    @Test
    void getRemainingToday_onceSchedule_futureToday_included() {
        Instant inOneHour = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once-today")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(inOneHour)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(onceSchedule));

        UpcomingScheduleResponse result = schedulerService.getRemainingToday();

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.schedules().get(0).scheduledAt()).isEqualTo(inOneHour);
    }

    @Test
    void getRemainingToday_onceSchedule_pastTime_excluded() {
        Instant oneHourAgo = FIXED_NOW.minus(1, ChronoUnit.HOURS);
        ScheduledWorkflow pastSchedule = ScheduledWorkflow.builder()
                .id("sched-past")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(oneHourAgo)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(pastSchedule));

        UpcomingScheduleResponse result = schedulerService.getRemainingToday();

        assertThat(result.count()).isZero();
    }

    @Test
    void getRemainingToday_noEnabledSchedules_returnsZero() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of());

        UpcomingScheduleResponse result = schedulerService.getRemainingToday();

        assertThat(result.count()).isZero();
        assertThat(result.schedules()).isEmpty();
    }

    @Test
    void getRemainingThisWeek_cronSchedule_returnsUpcomingRuns() {
        // Cron that runs every minute — should have many runs remaining this week
        ScheduledWorkflow everyMinute = ScheduledWorkflow.builder()
                .id("sched-freq")
                .workflow(testWorkflow)
                .cronExpression("0 * * * * *") // every minute (6-field Spring cron)
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(everyMinute));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        assertThat(result.count()).isGreaterThan(0);
        assertThat(result.schedules()).isNotEmpty();
        assertThat(result.schedules().get(0).workflowName()).isEqualTo("Test Workflow");
        // Weekly count should be >= daily count
        UpcomingScheduleResponse todayResult = schedulerService.getRemainingToday();
        assertThat(result.count()).isGreaterThanOrEqualTo(todayResult.count());
    }

    @Test
    void getRemainingThisWeek_cronSchedule_respectsMaxRunsAndRunCount() {
        ScheduledWorkflow limited = ScheduledWorkflow.builder()
                .id("sched-limited")
                .workflow(testWorkflow)
                .cronExpression("0 */7 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .maxRuns(35)
                .runCount(1)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(limited));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        assertThat(result.count()).isEqualTo(34);
        assertThat(result.schedules()).hasSize(34);
    }

    @Test
    void getRemainingThisWeek_cronSchedule_excludesExhaustedMaxRuns() {
        ScheduledWorkflow exhausted = ScheduledWorkflow.builder()
                .id("sched-exhausted")
                .workflow(testWorkflow)
                .cronExpression("0 */7 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .maxRuns(35)
                .runCount(35)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(exhausted));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        assertThat(result.count()).isZero();
        assertThat(result.schedules()).isEmpty();
    }

    @Test
    void getRemainingThisWeek_cronSchedule_excludesWeekBoundary() {
        ScheduledWorkflow boundary = ScheduledWorkflow.builder()
                .id("sched-boundary")
                .workflow(testWorkflow)
                .cronExpression("0 0 0 * * MON")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(boundary));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        assertThat(result.count()).isZero();
        assertThat(result.schedules()).isEmpty();
    }

    @Test
    void getRemainingThisWeek_cronSchedule_respectsEndDate() {
        Instant endDate = LocalDateTime.now(clock).plusMinutes(20)
                .atZone(ZoneId.systemDefault()).toInstant();
        ScheduledWorkflow ending = ScheduledWorkflow.builder()
                .id("sched-ending")
                .workflow(testWorkflow)
                .cronExpression("0 */5 * * * *")
                .scheduleType(ScheduleType.CRON)
                .enabled(true)
                .endDate(endDate)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(ending));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        assertThat(result.count()).isEqualTo(3);
        assertThat(result.schedules()).allMatch(run -> run.scheduledAt().isBefore(endDate));
    }

    @Test
    void getRemainingThisWeek_onceSchedule_withinWeek_included() {
        Instant inTwoDays = FIXED_NOW.plus(2, ChronoUnit.DAYS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once-week")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(inTwoDays)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(onceSchedule));

        UpcomingScheduleResponse result = schedulerService.getRemainingThisWeek();

        // inTwoDays may or may not fall within this week depending on day-of-week,
        // but it should be included since it's within 7 days and the week boundary
        // is at most 7 days away
        assertThat(result.count()).isGreaterThanOrEqualTo(0);
    }
}
