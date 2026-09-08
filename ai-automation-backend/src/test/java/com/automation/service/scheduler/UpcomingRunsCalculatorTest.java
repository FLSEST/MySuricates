package com.automation.service.scheduler;

import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ScheduledWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Calendar projection of enabled schedules. The first block of tests moved verbatim from
 * {@code SchedulerServiceTest}; the second block locks boundary semantics before the extraction
 * (Audit-TODO 05dbd0e9).
 */
@ExtendWith(MockitoExtension.class)
class UpcomingRunsCalculatorTest {

    @Mock
    private ScheduledWorkflowRepository scheduledRepository;

    // Fixed at noon UTC so getRemainingToday/Week behave deterministically regardless of wall-clock time.
    private static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    private UpcomingRunsCalculator calculator;
    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        calculator = new UpcomingRunsCalculator(scheduledRepository, clock);
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .build();
    }

    // --- moved from SchedulerServiceTest ---

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

        UpcomingScheduleResponse result = calculator.getRemainingToday();

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

        UpcomingScheduleResponse result = calculator.getRemainingToday();

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

        UpcomingScheduleResponse result = calculator.getRemainingToday();

        assertThat(result.count()).isZero();
    }

    @Test
    void getRemainingToday_noEnabledSchedules_returnsZero() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of());

        UpcomingScheduleResponse result = calculator.getRemainingToday();

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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

        assertThat(result.count()).isGreaterThan(0);
        assertThat(result.schedules()).isNotEmpty();
        assertThat(result.schedules().get(0).workflowName()).isEqualTo("Test Workflow");
        // Weekly count should be >= daily count
        UpcomingScheduleResponse todayResult = calculator.getRemainingToday();
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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

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

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

        // inTwoDays may or may not fall within this week depending on day-of-week,
        // but it should be included since it's within 7 days and the week boundary
        // is at most 7 days away
        assertThat(result.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void getNextRuns_validCron_returnsRequestedCount() {
        List<Instant> result = calculator.getNextRuns("0 0 * * * *", 3);

        assertThat(result).hasSize(3);
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i)).isAfter(result.get(i - 1));
        }
    }

    @Test
    void getNextRuns_invalidCron_throws() {
        assertThatThrownBy(() -> calculator.getNextRuns("bad-cron", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron expression");
    }

    @Test
    void getRemainingToday_delayedSchedule_futureToday_included() {
        Instant inOneHour = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow delayedSchedule = ScheduledWorkflow.builder()
                .id("sched-delayed-today")
                .workflow(testWorkflow)
                .cronExpression("")
                .scheduleType(ScheduleType.DELAYED)
                .scheduledAt(inOneHour)
                .enabled(true)
                .build();

        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(delayedSchedule));

        UpcomingScheduleResponse result = calculator.getRemainingToday();

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.schedules().get(0).scheduledAt()).isEqualTo(inOneHour);
    }

    // --- boundary semantics (characterization) ---

    private ScheduledWorkflow once(Instant at) {
        return ScheduledWorkflow.builder().id("once").workflow(testWorkflow).cronExpression("")
                .scheduleType(ScheduleType.ONCE).scheduledAt(at).enabled(true).build();
    }

    private static Instant endOfLocalDay() {
        return LocalDate.of(2026, 4, 26).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void getRemainingToday_onceExactlyNow_excluded_strictAfter() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(once(FIXED_NOW)));

        assertThat(calculator.getRemainingToday().count()).isZero();
    }

    @Test
    void getRemainingToday_onceExactlyAtEndOfDay_excluded_strictBefore() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(once(endOfLocalDay())));

        assertThat(calculator.getRemainingToday().count()).isZero();
    }

    @Test
    void getRemainingToday_onceJustBeforeEndOfDay_included() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(once(endOfLocalDay().minusSeconds(1))));

        assertThat(calculator.getRemainingToday().count()).isEqualTo(1);
    }

    @Test
    void getRemainingToday_cronMaxRunsZero_isUnlimited() {
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 * * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .maxRuns(0).runCount(100).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(sw));

        assertThat(calculator.getRemainingToday().count()).isGreaterThan(100);
    }

    @Test
    void getRemainingToday_cronMaxRuns_limitsToRemaining() {
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 * * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .maxRuns(3).runCount(1).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(sw));

        assertThat(calculator.getRemainingToday().count()).isEqualTo(2);
    }

    @Test
    void getRemainingToday_chainTarget_populatesChainFields_andNoWorkflowName() {
        WorkflowChain chain = WorkflowChain.builder().id("chain-1").name("Nightly").build();
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s").chain(chain).targetType(TargetType.CHAIN)
                .cronExpression("").scheduleType(ScheduleType.ONCE).scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS))
                .enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(sw));

        var run = calculator.getRemainingToday().schedules().get(0);

        assertThat(run.chainId()).isEqualTo("chain-1");
        assertThat(run.chainName()).isEqualTo("Nightly");
        assertThat(run.targetType()).isEqualTo("CHAIN");
        assertThat(run.workflowName()).isNull();
        assertThat(run.projectName()).isNull();
    }

    @Test
    void getRemainingToday_workflowProject_populatesProjectName() {
        Workflow withProject = Workflow.builder().id("wf-2").name("WF")
                .project(Project.builder().id("p-1").name("Alpha").build()).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(
                ScheduledWorkflow.builder().id("s").workflow(withProject).cronExpression("")
                        .scheduleType(ScheduleType.ONCE).scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).enabled(true).build()));

        var run = calculator.getRemainingToday().schedules().get(0);

        assertThat(run.projectName()).isEqualTo("Alpha");
        assertThat(run.workflowName()).isEqualTo("WF");
        assertThat(run.targetType()).isEqualTo("WORKFLOW");
    }

    @Test
    void getRemainingThisWeek_endsAtNextMondayLocalMidnight() {
        // FIXED_NOW is a Sunday: a daily 23:00 (local) cron has exactly one run left this week.
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 0 23 * * *").scheduleType(ScheduleType.CRON).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(sw));

        assertThat(calculator.getRemainingThisWeek().count()).isEqualTo(1);
    }

    @Test
    void getNextRuns_convertsCronOccurrencesWithSystemZone() {
        List<Instant> runs = calculator.getNextRuns("0 0 13 * * *", 1);

        assertThat(runs).containsExactly(
                LocalDateTime.of(2026, 4, 26, 13, 0).atZone(ZoneId.systemDefault()).toInstant());
    }
}
