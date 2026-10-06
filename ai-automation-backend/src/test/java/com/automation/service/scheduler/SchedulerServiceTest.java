package com.automation.service.scheduler;

import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Limit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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
                workflowRunRepository, chainRunRepository, clock, new ScheduleDefinitionParser(clock));
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

    /**
     * The list goes via {@code findAllWithTargets()}, not via {@code findAll()}: only the
     * join-fetch variant fetches workflow and chain along, instead of reloading them per row
     * (TODO c9ec9844).
     */
    @Test
    void findAll_returnsAll() {
        when(scheduledRepository.findAllWithTargets()).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findAll();

        assertThat(result).hasSize(1);
        verify(scheduledRepository, never()).findAll();
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

    // --- findByChainId ---

    @Test
    void findByChainId_returnsMatching() {
        when(scheduledRepository.findByChainId("chain-1")).thenReturn(List.of(testScheduled));

        List<ScheduledWorkflow> result = schedulerService.findByChainId("chain-1");

        assertThat(result).hasSize(1);
    }

    // findSchedulesInRange moved to UpcomingRunsCalculator.findWithNextRunBetween (TODO a776fd2a),
    // its delegation test with it: UpcomingRunsCalculatorTest.findWithNextRunBetween_withoutPause_*.

    // --- findWorkflowRunsByScheduleId ---

    /** Below the limit the list is already everything: no count query (TODO 86777fb4). */
    @Test
    void findWorkflowRunsByScheduleId_belowTheLimit_countsNothing() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").build();
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("sched-1", Limit.of(50)))
                .thenReturn(List.of(run));

        SchedulerService.NewestRuns<WorkflowRun> result = schedulerService.findWorkflowRunsByScheduleId("sched-1", 50);

        assertThat(result.runs()).containsExactly(run);
        assertThat(result.total()).isEqualTo(1);
        verify(workflowRunRepository, never()).countByTriggeredByScheduleId(any());
    }

    /** At the limit a query counts all runs – the UI then says "weitere" (TODO 86777fb4). */
    @Test
    void findWorkflowRunsByScheduleId_atTheLimit_countsAll() {
        List<WorkflowRun> newest = List.of(WorkflowRun.builder().id("run-2").build(), WorkflowRun.builder().id("run-1").build());
        when(workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("sched-1", Limit.of(2)))
                .thenReturn(newest);
        when(workflowRunRepository.countByTriggeredByScheduleId("sched-1")).thenReturn(26_000L);

        SchedulerService.NewestRuns<WorkflowRun> result = schedulerService.findWorkflowRunsByScheduleId("sched-1", 2);

        assertThat(result.runs()).isEqualTo(newest);
        assertThat(result.total()).isEqualTo(26_000L);
    }

    // --- findChainRunsByScheduleId ---

    @Test
    void findChainRunsByScheduleId_atTheLimit_countsAll() {
        ChainRun run = ChainRun.builder().id("crun-1").build();
        when(chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("sched-1", Limit.of(1)))
                .thenReturn(List.of(run));
        when(chainRunRepository.countByTriggeredByScheduleId("sched-1")).thenReturn(3L);

        SchedulerService.NewestRuns<ChainRun> result = schedulerService.findChainRunsByScheduleId("sched-1", 1);

        assertThat(result.runs()).containsExactly(run);
        assertThat(result.total()).isEqualTo(3L);
    }

    // --- findWorkflowRunsBetween ---

    @Test
    void findWorkflowRunsBetween_returnsTheNewestAndTheirTotal() {
        Instant from = FIXED_NOW.minus(1, ChronoUnit.HOURS);
        WorkflowRun run = WorkflowRun.builder().id("run-2").build();
        when(workflowRunRepository.findByStartedAtBetweenOrderByStartedAtDesc(from, FIXED_NOW, Limit.of(1)))
                .thenReturn(List.of(run));
        when(workflowRunRepository.countByStartedAtBetween(from, FIXED_NOW)).thenReturn(12L);

        SchedulerService.NewestRuns<WorkflowRun> result = schedulerService.findWorkflowRunsBetween(from, FIXED_NOW, 1);

        assertThat(result.runs()).containsExactly(run);
        assertThat(result.total()).isEqualTo(12L);
    }

    // --- update ---

    @Test
    void update_description_updatesDescription() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, "New description", null, null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getDescription()).isEqualTo("New description");
    }

    @Test
    void update_cronExpression_enabled_updatesNextRun() {
        testScheduled.setEnabled(true);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest("0 30 * * * *", null, null, null, null, null, null, null);
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

        UpdateScheduleRequest request = new UpdateScheduleRequest("0 30 * * * *", null, null, null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getCronExpression()).isEqualTo("0 30 * * * *");
        assertThat(result.getNextRunAt()).isNull();
    }

    @Test
    void update_invalidCronExpression_throws() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        UpdateScheduleRequest request = new UpdateScheduleRequest("not-a-cron", null, null, null, null, null, null, null);

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

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, newTime, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-once-upd", request);

        assertThat(result.getScheduledAt()).isEqualTo(newTime);
        assertThat(result.getNextRunAt()).isEqualTo(newTime);
    }

    @Test
    void update_endDateAndMaxRuns_updated() {
        Instant endDate = FIXED_NOW.plus(30, ChronoUnit.DAYS);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, endDate, 10, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getEndDate()).isEqualTo(endDate);
        assertThat(result.getMaxRuns()).isEqualTo(10);
    }

    @Test
    void update_catchUpOnResume_updated() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, null, null, true, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.isCatchUpOnResume()).isTrue();
    }

    @Test
    void update_conflictBehavior_updated() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, null, null, null, "OVERLAP");
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.OVERLAP);
    }

    @Test
    void update_withoutConflictBehavior_keepsIt() {
        testScheduled.setConflictBehavior(ScheduledWorkflow.ConflictBehavior.OVERLAP);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, "Only the description", null, null, null, null, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-1", request);

        assertThat(result.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.OVERLAP);
    }

    @Test
    void update_invalidConflictBehavior_throwsAndSavesNothing() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, null, null, null, "sometimes");

        assertThatThrownBy(() -> schedulerService.update("sched-1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflictBehavior muss SKIP oder OVERLAP sein");
        verify(scheduledRepository, never()).save(any());
    }

    // --- getNextRuns ---

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
                .hasMessageContaining("targetType muss WORKFLOW oder CHAIN sein");
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
                .hasMessageContaining("scheduleType muss CRON, ONCE oder DELAYED sein");
    }

    @Test
    void create_unresolvableWorkflowAndInvalidScheduleType_typeCheckWinsBeforeTargetLookup() {
        // Precedence: schedule-type parsing happens before the target repository lookup, so an
        // unresolvable workflowId never reaches the repository when the type is already invalid.
        assertThatThrownBy(() -> schedulerService.create(
                "unknown-wf", null, null, "0 0 * * * *", "Test", null, "INVALID_TYPE", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleType muss CRON, ONCE oder DELAYED sein");

        verify(workflowRepository, never()).findById(any());
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

    // --- create: conflictBehavior (TODO a9bf13d5: parsed like on update) ---

    @Test
    void create_conflictBehavior_trimmedAndInAnyCase_missingMeansSkip() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow overlap = schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, "CRON", null, null, null, null, " overlap ", null);
        ScheduledWorkflow missing = schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, "CRON", null, null, null, null, " ", null);

        assertThat(overlap.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.OVERLAP);
        assertThat(missing.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.SKIP);
    }

    /** Before, an unknown value quietly became SKIP on create, while update already answered 400. */
    @Test
    void create_unknownConflictBehavior_throwsAndSavesNothing() {
        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", null, null, "0 0 * * * *", "Test", null, "CRON", null, null, null, null, "sometimes", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("conflictBehavior muss SKIP oder OVERLAP sein, nicht 'sometimes'");
        verify(scheduledRepository, never()).save(any());
    }

    // --- update: inputContext ---

    @Test
    void update_inputContext_updated() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> newCtx = Map.of("env", "staging");
        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, newCtx, null, null, null, null, null);
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

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, newTime, null, null, null, null);
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

        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, null, endDate, 5, null, null);
        ScheduledWorkflow result = schedulerService.update("sched-once-end", request);

        assertThat(result.getEndDate()).isNull();
        assertThat(result.getMaxRuns()).isZero();
    }

    // --- getRemainingToday: DELAYED schedule treated like ONCE ---

    // --- No run left: create, update and enable refuse the schedule (TODO 1e801952) ---

    private Instant firstHourlyRun() {
        return new ScheduleDefinitionParser(clock).calculateNextRun("0 0 * * * *");
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorMessage key) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LocalizedArgumentException.class,
                e -> assertThat(e.errorMessage()).isEqualTo(key));
    }

    @Test
    void create_endDateNotAfterTheFirstRun_isRefused() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertRefused(() -> schedulerService.create("wf-1", null, null, "0 0 * * * *", "x", null, "CRON", null, null,
                firstHourlyRun(), null, null, null), ErrorMessage.SCHEDULE_NEXT_RUN_AFTER_END_DATE);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void create_endDateAfterTheFirstRun_isAccepted() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create("wf-1", null, null, "0 0 * * * *", "x", null, "CRON", null,
                null, firstHourlyRun().plusSeconds(1), 1, null, null);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isEqualTo(firstHourlyRun());
    }

    /** The bundle import creates schedules disabled: an end date long past must not fail the import. */
    @Test
    void createDisabled_keepsAScheduleWhoseEndDateHasPassed() {
        Instant endDate = FIXED_NOW.minus(30, ChronoUnit.DAYS);
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.createDisabled("wf-1", null, null, "0 0 * * * *", "x", null,
                "CRON", null, null, endDate, 5, null, null);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getNextRunAt()).isNull();
        assertThat(result.getEndDate()).isEqualTo(endDate);
        assertThat(result.getMaxRuns()).isEqualTo(5);
    }

    @Test
    void update_endDateMovedToTheNextRun_isRefused() {
        testScheduled.setNextRunAt(firstHourlyRun());
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.update("sched-1", new UpdateScheduleRequest(null, null, null, null,
                firstHourlyRun(), null, null, null)), ErrorMessage.SCHEDULE_NEXT_RUN_AFTER_END_DATE);
        verify(scheduledRepository, never()).save(any());
    }

    /** Before: maxRuns lowered under runCount still let the next occurrence start once. */
    @Test
    void update_maxRunsLoweredToTheRunCount_isRefused() {
        testScheduled.setNextRunAt(firstHourlyRun());
        testScheduled.setRunCount(3);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.update("sched-1", new UpdateScheduleRequest(null, null, null, null,
                null, 3, null, null)), ErrorMessage.SCHEDULE_MAX_RUNS_REACHED);
        verify(scheduledRepository, never()).save(any());
    }

    /** The form sends maxRuns with every edit: a finished (disabled) schedule stays editable. */
    @Test
    void update_disabledScheduleWithItsRunsUsedUp_staysEditable() {
        testScheduled.setEnabled(false);
        testScheduled.setNextRunAt(null);
        testScheduled.setRunCount(3);
        testScheduled.setMaxRuns(3);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.update("sched-1", new UpdateScheduleRequest("0 0 * * * *",
                "renamed", null, null, FIXED_NOW.minus(1, ChronoUnit.DAYS), 3, null, null));

        assertThat(result.getDescription()).isEqualTo("renamed");
        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void enable_runsUsedUp_isRefused() {
        testScheduled.setEnabled(false);
        testScheduled.setRunCount(5);
        testScheduled.setMaxRuns(5);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.enable("sched-1"), ErrorMessage.SCHEDULE_MAX_RUNS_REACHED);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void enable_endDatePassed_isRefused() {
        testScheduled.setEnabled(false);
        testScheduled.setEndDate(FIXED_NOW.minus(1, ChronoUnit.DAYS));
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.enable("sched-1"), ErrorMessage.SCHEDULE_NEXT_RUN_AFTER_END_DATE);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void enable_cronThatNoLongerParses_isRefusedAsInvalidCron() {
        testScheduled.setEnabled(false);
        testScheduled.setCronExpression("not a cron");
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.enable("sched-1"), ErrorMessage.INVALID_CRON);
        verify(scheduledRepository, never()).save(any());
    }

    // --- A cron without a future occurrence, a one-off in the past (TODO 1e801952) ---

    @Test
    void create_cronWithoutAFutureOccurrence_isRefused() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertRefused(() -> schedulerService.create("wf-1", null, null, "0 0 0 30 2 *", "x", null, "CRON", null, null,
                null, null, null, null), ErrorMessage.SCHEDULE_NO_NEXT_RUN);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void update_cronWithoutAFutureOccurrence_isRefused() {
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.update("sched-1", new UpdateScheduleRequest("0 0 0 30 2 *", null, null,
                null, null, null, null, null)), ErrorMessage.SCHEDULE_NO_NEXT_RUN);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void enable_cronWithoutAFutureOccurrence_isRefused() {
        testScheduled.setEnabled(false);
        testScheduled.setCronExpression("0 0 0 30 2 *");
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));

        assertRefused(() -> schedulerService.enable("sched-1"), ErrorMessage.SCHEDULE_NO_NEXT_RUN);
        verify(scheduledRepository, never()).save(any());
    }

    private ScheduledWorkflow onceAt(Instant at, boolean enabled) {
        return ScheduledWorkflow.builder().id("sched-once-past").workflow(testWorkflow).cronExpression("")
                .scheduleType(ScheduleType.ONCE).scheduledAt(at).enabled(enabled).build();
    }

    @Test
    void create_onceInThePast_isRefused() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertRefused(() -> schedulerService.create("wf-1", null, null, null, "x", null, "ONCE",
                FIXED_NOW.minusSeconds(61), null, null, null, null, null), ErrorMessage.SCHEDULE_ONCE_IN_PAST);
        verify(scheduledRepository, never()).save(any());
    }

    /** Before: enabling a one-off whose time had passed started it right away. */
    @Test
    void enable_onceInThePast_isRefused() {
        ScheduledWorkflow once = onceAt(FIXED_NOW.minus(1, ChronoUnit.DAYS), false);
        when(scheduledRepository.findById("sched-once-past")).thenReturn(Optional.of(once));

        assertRefused(() -> schedulerService.enable("sched-once-past"), ErrorMessage.SCHEDULE_ONCE_IN_PAST);
        verify(scheduledRepository, never()).save(any());
    }

    @Test
    void update_onceMovedIntoThePast_isRefused() {
        ScheduledWorkflow once = onceAt(FIXED_NOW.plus(1, ChronoUnit.HOURS), true);
        when(scheduledRepository.findById("sched-once-past")).thenReturn(Optional.of(once));

        assertRefused(() -> schedulerService.update("sched-once-past", new UpdateScheduleRequest(null, null, null,
                FIXED_NOW.minusSeconds(61), null, null, null, null)), ErrorMessage.SCHEDULE_ONCE_IN_PAST);
        verify(scheduledRepository, never()).save(any());
    }

    /** The bundle import keeps a past one-off — disabled, it never starts. */
    @Test
    void createDisabled_keepsAOneOffInThePast() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.createDisabled("wf-1", null, null, null, "x", null, "ONCE",
                FIXED_NOW.minus(1, ChronoUnit.DAYS), null, null, null, null, null);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getScheduledAt()).isEqualTo(FIXED_NOW.minus(1, ChronoUnit.DAYS));
    }

    // --- Review: tolerance for a one-off in the running minute, edits of old data (TODO 1e801952) ---

    /** A time picked in the running minute is not "in the past": it starts with the next tick. */
    @Test
    void create_onceWithinTheRunningMinute_isAccepted() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create("wf-1", null, null, null, "x", null, "ONCE",
                FIXED_NOW.minusSeconds(60), null, null, null, null, null);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isEqualTo(FIXED_NOW.minusSeconds(60));
    }

    @Test
    void update_onceMovedWithinTheRunningMinute_isAccepted() {
        ScheduledWorkflow once = onceAt(FIXED_NOW.plus(1, ChronoUnit.HOURS), true);
        when(scheduledRepository.findById("sched-once-past")).thenReturn(Optional.of(once));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.update("sched-once-past", new UpdateScheduleRequest(null, null,
                null, FIXED_NOW.minusSeconds(30), null, null, null, null));

        assertThat(result.getNextRunAt()).isEqualTo(FIXED_NOW.minusSeconds(30));
    }

    /** Old data with its runs used up: changing only the description must not be refused. */
    @Test
    void update_descriptionOnly_ofAnEnabledScheduleWithoutARunLeft_isAccepted() {
        testScheduled.setNextRunAt(firstHourlyRun());
        testScheduled.setRunCount(5);
        testScheduled.setMaxRuns(5);
        testScheduled.setEndDate(FIXED_NOW.minus(1, ChronoUnit.DAYS));
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.update("sched-1", new UpdateScheduleRequest(null, "renamed",
                Map.of("k", "v"), null, null, null, true, "OVERLAP"));

        assertThat(result.getDescription()).isEqualTo("renamed");
    }
}
