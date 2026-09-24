package com.automation.service.assistant.context;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.ScheduleSkipPolicy;
import com.automation.service.scheduler.UpcomingRunsCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulesContextSectionTest {

    @Mock private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Mock private ActivityLogService activityLogService;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    /** Real config: skip-until is empty unless a test sets a pause. */
    private final SchedulerConfig schedulerConfig = new SchedulerConfig();

    private SchedulesContextSection section;

    @BeforeEach
    void setUp() {
        section = new SchedulesContextSection(new UpcomingRunsCalculator(scheduledWorkflowRepository,
                Clock.systemDefaultZone(),
                new ScheduleSkipPolicy(schedulerConfig, scheduledWorkflowRepository, activityLogService)));
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

    /** During a skip-until pause the assistant names the run that really comes (TODO a776fd2a). */
    @Test
    void full_duringSkipUntilPause_namesTheRunAfterThePause_andDropsTheExpiringOneOff() {
        Instant pauseEnd = Instant.now().plus(2, ChronoUnit.HOURS);
        schedulerConfig.setSkipUntil(pauseEnd.toString());
        Instant stored = Instant.now().plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow hourly = buildSchedule("Hourly Report", stored);
        hourly.setScheduleType(ScheduledWorkflow.ScheduleType.CRON);
        hourly.setCronExpression("0 0 * * * *");
        ScheduledWorkflow oneOff = buildSchedule("One-off Import", stored);
        oneOff.setScheduleType(ScheduledWorkflow.ScheduleType.ONCE);
        oneOff.setScheduledAt(stored);
        when(scheduledWorkflowRepository.findSchedulesInRange(eq(Instant.EPOCH), any()))
                .thenReturn(List.of(hourly, oneOff));
        Instant afterPause = CronExpression.parse("0 0 * * * *")
                .next(LocalDateTime.ofInstant(pauseEnd, ZoneId.systemDefault()).minusNanos(1))
                .atZone(ZoneId.systemDefault()).toInstant();

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("Hourly Report: " + FMT.format(afterPause))
                .doesNotContain("One-off Import");
    }
}
