package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledWorkflowExecutorTest {

    @Mock
    private ScheduledWorkflowRepository scheduledRepository;

    @Mock
    private WorkflowExecutor workflowExecutor;

    @Mock
    private ActivityLogService activityLogService;

    @InjectMocks
    private ScheduledWorkflowExecutor executor;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
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

        when(scheduledRepository.findById("sched-cron")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        executor.executeSingleSchedule("sched-cron");

        verify(workflowExecutor).execute(testWorkflow, Map.of());
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

        when(scheduledRepository.findById("sched-once")).thenReturn(Optional.of(scheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        executor.executeSingleSchedule("sched-once");

        verify(workflowExecutor).execute(testWorkflow, Map.of());
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

        when(scheduledRepository.findById("sched-future")).thenReturn(Optional.of(scheduled));

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

        when(scheduledRepository.findById("sched-disabled")).thenReturn(Optional.of(scheduled));

        executor.executeSingleSchedule("sched-disabled");

        verify(workflowExecutor, never()).execute(any(), any());
    }

    @Test
    void executeSingleSchedule_nonExistent_doesNothing() {
        when(scheduledRepository.findById("unknown")).thenReturn(Optional.empty());

        executor.executeSingleSchedule("unknown");

        verify(workflowExecutor, never()).execute(any(), any());
    }
}
