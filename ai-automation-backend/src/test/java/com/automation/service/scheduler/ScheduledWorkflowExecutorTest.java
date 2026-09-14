package com.automation.service.scheduler;

import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @InjectMocks
    private ScheduledWorkflowExecutor executor;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(false);

        executor.executeSingleSchedule("sched-cron");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), any(String.class), isNull());
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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(false);

        executor.executeSingleSchedule("sched-once");

        verify(workflowExecutor).execute(eq(testWorkflow), eq(Map.of()), any(String.class), isNull());
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

        verify(workflowExecutor, never()).execute(any(), any());
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

        verify(workflowExecutor, never()).execute(any(), any());
    }

    @Test
    void executeSingleSchedule_nonExistent_doesNothing() {
        when(scheduledRepository.findByIdWithWorkflowSkills("unknown")).thenReturn(Optional.empty());

        executor.executeSingleSchedule("unknown");

        verify(workflowExecutor, never()).execute(any(), any());
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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(true);

        executor.executeSingleSchedule("sched-ghost");

        verify(workflowExecutor, never()).execute(any(), any());
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
        when(chainRunRepository.existsByChainIdAndStatus(eq("chain-1"), eq(RunStatus.RUNNING))).thenReturn(true);

        executor.executeSingleSchedule("sched-chain-ghost");

        verify(chainExecutor, never()).execute(any(), any());
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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(false);
        doThrow(new RuntimeException("LazyInitializationException: no Session"))
                .when(workflowExecutor).execute(any(), any());

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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(false);
        when(workflowExecutor.execute(eq(testWorkflow), any(), eq("sched-ref"), isNull()))
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
        when(workflowRunRepository.existsByWorkflowIdAndStatus(eq("wf-1"), eq(RunStatus.RUNNING))).thenReturn(true);

        assertThat(executor.executeSingleSchedule("sched-busy")).isEmpty();
        verify(workflowExecutor, never()).execute(any(), any(), any(), any());
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

}
