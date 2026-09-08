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
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.entity.ChainRun;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import java.time.Duration;
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

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ChainRunRepository chainRunRepository;

    // Fixed at noon UTC so getRemainingToday/Week behave deterministically regardless of wall-clock time.
    private static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    private SchedulerService schedulerService;

    private Workflow testWorkflow;
    private ScheduledWorkflow testScheduled;
    private WorkflowChain testChain;

    @BeforeEach
    void setUp() {
        schedulerService = new SchedulerService(scheduledRepository, workflowRepository, workflowChainRepository,
                workflowRunRepository, chainRunRepository, scheduledExecutor, activityLogService,
                appSettingsRepository, schedulerConfig, suspendState, clock,
                new ScheduleDefinitionParser(clock),
                new ScheduleSkipPolicy(schedulerConfig, scheduledRepository, activityLogService));
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

        testChain = WorkflowChain.builder()
                .id("chain-1")
                .name("Test Chain")
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
    void checkAndExecuteScheduled_resumeAfterSuspend_globalCatchUpEnabled_executesScheduleWithPerScheduleCatchUpFalse() {
        // Global catch-up=true overrides per-schedule catchUpOnResume=false → should EXECUTE, not skip.
        ReflectionTestUtils.setField(schedulerService, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(schedulerConfig.isCatchUpOnResume()).thenReturn(true);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));

        schedulerService.checkAndExecuteScheduled();

        verify(scheduledExecutor).executeSingleSchedule("sched-1");
        verify(scheduledRepository, never()).save(any());
        verify(suspendState).recordResume(eq(FIXED_NOW), anyLong());
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

    // --- findByChainId ---

    @Test
    void findByChainId_returnsMatching() {
        when(scheduledRepository.findByChainId("chain-1")).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findByChainId("chain-1");

        assertThat(result).hasSize(1);
    }

    // --- findSchedulesInRange ---

    @Test
    void findSchedulesInRange_delegatesToRepository() {
        Instant from = FIXED_NOW;
        Instant to = FIXED_NOW.plus(1, ChronoUnit.DAYS);
        when(scheduledRepository.findSchedulesInRange(from, to)).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findSchedulesInRange(from, to);

        assertThat(result).hasSize(1);
    }

    // --- findWorkflowRunsByScheduleId ---

    @Test
    void findWorkflowRunsByScheduleId_delegatesToRepository() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").build();
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("sched-1"))
                .thenReturn(List.of(run));

        List<WorkflowRun> result = schedulerService.findWorkflowRunsByScheduleId("sched-1");

        assertThat(result).hasSize(1);
    }

    // --- findChainRunsByScheduleId ---

    @Test
    void findChainRunsByScheduleId_delegatesToRepository() {
        ChainRun run = ChainRun.builder().id("crun-1").build();
        when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("sched-1"))
                .thenReturn(List.of(run));

        List<ChainRun> result = schedulerService.findChainRunsByScheduleId("sched-1");

        assertThat(result).hasSize(1);
    }

    // --- findWorkflowRunsBetween ---

    @Test
    void findWorkflowRunsBetween_delegatesToRepository() {
        Instant from = FIXED_NOW.minus(1, ChronoUnit.HOURS);
        WorkflowRun run = WorkflowRun.builder().id("run-2").build();
        when(workflowRunRepository.findByStartedAtBetween(from, FIXED_NOW)).thenReturn(List.of(run));

        List<WorkflowRun> result = schedulerService.findWorkflowRunsBetween(from, FIXED_NOW);

        assertThat(result).hasSize(1);
    }

    // --- update ---

    @Test
    void update_description_updatesDescription() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, "New description", null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getDescription()).isEqualTo("New description");
    }

    @Test
    void update_cronExpression_enabled_updatesNextRun() {
        testScheduled.setEnabled(true);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest("0 30 * * * *", null, null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getCronExpression()).isEqualTo("0 30 * * * *");
        assertThat(result.getNextRunAt()).isNotNull();
    }

    @Test
    void update_cronExpression_disabled_doesNotUpdateNextRun() {
        testScheduled.setEnabled(false);
        testScheduled.setNextRunAt(null);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest("0 30 * * * *", null, null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getCronExpression()).isEqualTo("0 30 * * * *");
        assertThat(result.getNextRunAt()).isNull();
    }

    @Test
    void update_invalidCronExpression_throws() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        UpdateScheduleRequest request = new UpdateScheduleRequest("not-a-cron", null, null, null, null, null, null);

        assertThatThrownBy(() -> schedulerService.update("sched-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void update_onceScheduledAt_enabled_updatesScheduledAtAndNextRun() {
        Instant newTime = FIXED_NOW.plus(3, ChronoUnit.HOURS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once-upd")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS))
                .enabled(true)
                .build();

        when(scheduledRepository.findById("sched-once-upd")).thenReturn(Optional.of(onceSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, newTime, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-once-upd", request);

        assertThat(result.getScheduledAt()).isEqualTo(newTime);
        assertThat(result.getNextRunAt()).isEqualTo(newTime);
    }

    @Test
    void update_endDateAndMaxRuns_updated() {
        Instant endDate = FIXED_NOW.plus(30, ChronoUnit.DAYS);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, endDate, 10, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getEndDate()).isEqualTo(endDate);
        assertThat(result.getMaxRuns()).isEqualTo(10);
    }

    @Test
    void update_catchUpOnResume_updated() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, null, null, true);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.isCatchUpOnResume()).isTrue();
    }

    // --- getNextRuns ---

    // --- isMaintenanceMode / setMaintenanceMode ---

    @Test
    void isMaintenanceMode_whenTrue_returnsTrue() {
        when(appSettingsRepository.get("scheduler.maintenance-mode")).thenReturn(Optional.of("true"));

        assertThat(schedulerService.isMaintenanceMode()).isTrue();
    }

    @Test
    void isMaintenanceMode_whenNotSet_returnsFalse() {
        when(appSettingsRepository.get("scheduler.maintenance-mode")).thenReturn(Optional.empty());

        assertThat(schedulerService.isMaintenanceMode()).isFalse();
    }

    @Test
    void setMaintenanceMode_enabled_setsValue() {
        schedulerService.setMaintenanceMode(true);

        verify(appSettingsRepository).set("scheduler.maintenance-mode", "true");
    }

    @Test
    void setMaintenanceMode_disabled_setsValue() {
        schedulerService.setMaintenanceMode(false);

        verify(appSettingsRepository).set("scheduler.maintenance-mode", "false");
    }

    // --- create: CHAIN target type ---

    @Test
    void create_chainSchedule_success() {
        when(workflowChainRepository.findById("chain-1")).thenReturn(Optional.of(testChain));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> {
            ScheduledWorkflow s = inv.getArgument(0);
            s.setId("sched-chain");
            return s;
        });

        ScheduledWorkflow result = schedulerService.create(
                null, "chain-1", "CHAIN", "0 0 * * * *", "Chain schedule",
                null, "CRON", null, null, null, null, null, null);

        assertThat(result.getId()).isEqualTo("sched-chain");
        assertThat(result.getTargetType()).isEqualTo(ScheduledWorkflow.TargetType.CHAIN);
        assertThat(result.getChain()).isEqualTo(testChain);
        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void create_chainSchedule_missingChainId_throws() {
        assertThatThrownBy(() -> schedulerService.create(
                null, null, "CHAIN", "0 0 * * * *", "Test", null, "CRON", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chainId is required");
    }

    @Test
    void create_invalidTargetType_throws() {
        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, "INVALID", "0 0 * * * *", "Test", null, "CRON", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid target type");
    }

    // --- create: DELAYED schedule type ---

    @Test
    void create_delayedSchedule_success() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", null, null, null, "Delayed run",
                null, "DELAYED", null, "2h", null, null, null, null);

        assertThat(result.getScheduleType()).isEqualTo(ScheduleType.DELAYED);
        assertThat(result.getDelayExpression()).isEqualTo("2h");
        assertThat(result.getScheduledAt()).isNotNull().isAfter(FIXED_NOW);
        assertThat(result.getNextRunAt()).isEqualTo(result.getScheduledAt());
        assertThat(result.getCronExpression()).isEmpty();
    }

    @Test
    void create_delayedSchedule_missingDelayExpression_throws() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, null, "Test", null, "DELAYED", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delayExpression is required");
    }

    // --- parseDelay ---

    // --- enable: DELAYED schedule ---

    @Test
    void enable_delayedSchedule_setsScheduledAt() {
        Instant futureTime = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow delayedSchedule = ScheduledWorkflow.builder()
                .id("sched-delayed")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.DELAYED)
                .scheduledAt(futureTime)
                .enabled(false)
                .build();

        when(scheduledRepository.findById("sched-delayed")).thenReturn(Optional.of(delayedSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.enable("sched-delayed");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isEqualTo(futureTime);
    }

    // --- create: missing workflowId for WORKFLOW target ---

    @Test
    void create_workflowSchedule_nullWorkflowId_throws() {
        assertThatThrownBy(() -> schedulerService.create(
                null, null, "WORKFLOW", "0 0 * * * *", "Test", null, "CRON", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowId is required");
    }

    // --- create: nonexistent chain ---

    @Test
    void create_chainSchedule_nonExistentChain_throws() {
        when(workflowChainRepository.findById("unknown-chain")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> schedulerService.create(
                null, "unknown-chain", "CHAIN", "0 0 * * * *", "Test", null, "CRON", null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- create: invalid scheduleType ---

    @Test
    void create_invalidScheduleType_throws() {
        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, "INVALID_TYPE", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schedule type");
    }

    // --- create: catchUpOnResume is persisted ---

    @Test
    void create_catchUpOnResume_true_isSet() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, "CRON", null, null, null, null, null, true);

        assertThat(result.isCatchUpOnResume()).isTrue();
    }

    // --- update: inputContext ---

    @Test
    void update_inputContext_updated() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> newCtx = Map.of("env", "staging");
        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, newCtx, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getInputContext()).isEqualTo(newCtx);
    }

    // --- update: ONCE schedule, disabled — scheduledAt updated, nextRun stays null ---

    @Test
    void update_onceScheduledAt_disabled_doesNotUpdateNextRun() {
        Instant newTime = FIXED_NOW.plus(3, ChronoUnit.HOURS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once-disabled")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS))
                .enabled(false)
                .nextRunAt(null)
                .build();

        when(scheduledRepository.findById("sched-once-disabled")).thenReturn(Optional.of(onceSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, newTime, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-once-disabled", request);

        assertThat(result.getScheduledAt()).isEqualTo(newTime);
        assertThat(result.getNextRunAt()).isNull();
    }

    // --- update: endDate/maxRuns ignored for ONCE schedule ---

    @Test
    void update_endDateAndMaxRuns_ignoredForOnceSchedule() {
        Instant endDate = FIXED_NOW.plus(30, ChronoUnit.DAYS);
        ScheduledWorkflow onceSchedule = ScheduledWorkflow.builder()
                .id("sched-once-end")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.ONCE)
                .scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS))
                .enabled(true)
                .build();

        when(scheduledRepository.findById("sched-once-end")).thenReturn(Optional.of(onceSchedule));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, endDate, 5, null);
        ScheduledWorkflow result = schedulerService.update("sched-once-end", request);

        assertThat(result.getEndDate()).isNull();
        assertThat(result.getMaxRuns()).isZero();
    }

    // --- getRemainingToday: DELAYED schedule treated like ONCE ---

    // --- checkAndExecuteScheduled: resume emits ActivityLog warn ---

    @Test
    void checkAndExecuteScheduled_resumeAfterSuspend_warnsActivityLog() {
        ReflectionTestUtils.setField(schedulerService, "lastTickAt", FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setNextRunAt(FIXED_NOW.minus(3, ChronoUnit.DAYS));
        testScheduled.setCatchUpOnResume(false);

        when(schedulerConfig.getResumeGraceMinutes()).thenReturn(5L);
        when(scheduledRepository.findDueSchedules(FIXED_NOW)).thenReturn(List.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        schedulerService.checkAndExecuteScheduled();

        verify(activityLogService).warn(
                eq(LogCategory.SCHEDULE),
                contains("System resume detected"),
                eq("SchedulerService")
        );
    }
}
