package com.automation.service.assistant.context;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.repository.ScheduledWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulesContextSectionTest {

    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;

    private SchedulesContextSection section;

    @BeforeEach
    void setUp() {
        section = new SchedulesContextSection(scheduledWorkflowRepository);
    }

    private ScheduledWorkflow buildSchedule(String workflowName, Instant nextRun) {
        ScheduledWorkflow sw = new ScheduledWorkflow();
        sw.setNextRunAt(nextRun);
        if (workflowName != null) {
            Workflow wf = new Workflow();
            wf.setName(workflowName);
            sw.setWorkflow(wf);
        }
        return sw;
    }

    @Test
    void compact_showsCount() {
        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any()))
                .thenReturn(List.of(
                        buildSchedule("Daily Report", Instant.now().plusSeconds(3600)),
                        buildSchedule("Nightly Build", Instant.now().plusSeconds(7200))
                ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).contains("2 upcoming (next 24h)");
    }

    @Test
    void compact_noSchedules_appendsNothing() {
        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any()))
                .thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsScheduleDetails() {
        ScheduledWorkflow sw = buildSchedule("Daily Report", Instant.now().plusSeconds(3600));
        Workflow wf = new Workflow();
        wf.setName("Report Workflow");
        sw.setWorkflow(wf);

        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any()))
                .thenReturn(List.of(sw));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Upcoming Schedules (next 24h):")
                .contains("Report Workflow:");
    }

    @Test
    void full_noWorkflow_usesTargetName() {
        ScheduledWorkflow sw = new ScheduledWorkflow();
        sw.setNextRunAt(Instant.now().plusSeconds(3600));
        // No workflow set → getTargetName() returns "Unknown"
        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any()))
                .thenReturn(List.of(sw));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("Unknown:");
    }

    @Test
    void exception_doesNotThrow() {
        when(scheduledWorkflowRepository.findSchedulesInRange(any(), any()))
                .thenThrow(new RuntimeException("error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}
