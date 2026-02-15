package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
    private ScheduledWorkflowExecutor scheduledExecutor;

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
    void create_validCron_createsSchedule() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> {
            ScheduledWorkflow s = inv.getArgument(0);
            s.setId("sched-new");
            return s;
        });

        ScheduledWorkflow result = schedulerService.create(
                "wf-1", "0 0 * * * *", "Hourly", Map.of("env", "prod"));

        assertThat(result.getId()).isEqualTo("sched-new");
        assertThat(result.getCronExpression()).isEqualTo("0 0 * * * *");
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
        verify(scheduledRepository).save(any());
    }

    @Test
    void create_invalidCron_throwsException() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        assertThatThrownBy(() -> schedulerService.create(
                "wf-1", "invalid-cron", "Test", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void create_nonExistentWorkflow_throwsException() {
        when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> schedulerService.create(
                "unknown", "0 0 * * * *", "Test", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_nullInputContext_defaultsToEmptyMap() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(scheduledRepository.save(any(ScheduledWorkflow.class))).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.create("wf-1", "0 0 * * * *", "Test", null);

        assertThat(result.getInputContext()).isNotNull().isEmpty();
    }

    @Test
    void enable_setsEnabledAndNextRun() {
        testScheduled.setEnabled(false);
        testScheduled.setNextRunAt(null);
        when(scheduledRepository.findById("sched-1")).thenReturn(Optional.of(testScheduled));
        when(scheduledRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScheduledWorkflow result = schedulerService.enable("sched-1");

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNextRunAt()).isNotNull();
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

        // Should still attempt second schedule despite first failing
        verify(scheduledExecutor).executeSingleSchedule("s1");
        verify(scheduledExecutor).executeSingleSchedule("s2");
    }
}
