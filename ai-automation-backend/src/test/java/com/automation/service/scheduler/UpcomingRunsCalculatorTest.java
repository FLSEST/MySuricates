package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private ActivityLogService activityLogService;

    /** Off unless a test switches it on (TODO 7704b1e9). */
    @Mock
    private SchedulerMaintenanceMode maintenanceMode;

    // Fixed at noon UTC so getRemainingToday/Week behave deterministically regardless of wall-clock time.
    private static final Instant FIXED_NOW = Instant.parse("2026-04-26T12:00:00Z");
    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));

    /** Real config: skip-until is empty unless a test sets a pause. */
    private final SchedulerConfig schedulerConfig = new SchedulerConfig();

    private UpcomingRunsCalculator calculator;
    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        calculator = new UpcomingRunsCalculator(scheduledRepository, clock,
                new ScheduleSkipPolicy(schedulerConfig, scheduledRepository, activityLogService, maintenanceMode));
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

    /** Dashboard project filter: only schedules of that project's workflows; chains have no project (TODO 4e1c9ad4). */
    @Test
    void getRemainingToday_withProject_keepsOnlyThatProjectsWorkflows() {
        Workflow mine = Workflow.builder().id("wf-a").name("Mine")
                .project(Project.builder().id("p-a").name("A").build()).build();
        Workflow other = Workflow.builder().id("wf-b").name("Other")
                .project(Project.builder().id("p-b").name("B").build()).build();
        Instant soon = FIXED_NOW.plus(1, ChronoUnit.HOURS);
        ScheduledWorkflow mineOnce = once("s-a", mine, soon);
        ScheduledWorkflow otherOnce = once("s-b", other, soon);
        ScheduledWorkflow chainOnce = ScheduledWorkflow.builder().id("s-c").targetType(TargetType.CHAIN)
                .chain(WorkflowChain.builder().id("c-1").name("Chain").build())
                .scheduleType(ScheduleType.ONCE).scheduledAt(soon).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(mineOnce, otherOnce, chainOnce));

        assertThat(calculator.getRemainingToday("p-a").schedules())
                .extracting(UpcomingScheduleResponse.UpcomingRun::scheduleId).containsExactly("s-a");
        assertThat(calculator.getRemainingThisWeek("p-b").count()).isEqualTo(1);
        assertThat(calculator.getRemainingToday(null).count()).isEqualTo(3);
    }

    private static ScheduledWorkflow once(String id, Workflow workflow, Instant at) {
        return ScheduledWorkflow.builder().id(id).workflow(workflow)
                .scheduleType(ScheduleType.ONCE).scheduledAt(at).enabled(true).build();
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

    // --- skip-until pause (TODO a776fd2a): runs inside it are skipped, not upcoming ---

    @Test
    void remainingThisWeek_duringPause_startsAtItsEnd_andSaysUntilWhen() {
        Instant pauseEnd = FIXED_NOW.plus(3, ChronoUnit.HOURS);
        schedulerConfig.setSkipUntil(pauseEnd.toString());
        ScheduledWorkflow everyMinute = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 * * * * *").scheduleType(ScheduleType.CRON).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(everyMinute));

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

        assertThat(result.pausedUntil()).isEqualTo(pauseEnd);
        assertThat(result.schedules()).isNotEmpty()
                .allMatch(run -> !run.scheduledAt().isBefore(pauseEnd));
        // An occurrence exactly at the end of the pause runs — the tick lets it through.
        assertThat(result.schedules().get(0).scheduledAt()).isEqualTo(pauseEnd);
    }

    @Test
    void remainingToday_pauseBeyondToday_leavesNoRunToday() {
        Instant pauseEnd = FIXED_NOW.plus(3, ChronoUnit.DAYS);
        schedulerConfig.setSkipUntil(pauseEnd.toString());
        ScheduledWorkflow everyMinute = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 * * * * *").scheduleType(ScheduleType.CRON).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(everyMinute, once(FIXED_NOW.plus(1, ChronoUnit.HOURS))));

        UpcomingScheduleResponse result = calculator.getRemainingToday();

        assertThat(result.count()).isZero();
        assertThat(result.pausedUntil()).isEqualTo(pauseEnd);
    }

    @Test
    void remainingToday_oneOffInsideThePauseIsDropped_oneOffAfterItStays() {
        schedulerConfig.setSkipUntil(FIXED_NOW.plus(2, ChronoUnit.HOURS).toString());
        ScheduledWorkflow inside = once(FIXED_NOW.plus(1, ChronoUnit.HOURS));
        ScheduledWorkflow after = ScheduledWorkflow.builder().id("once-after").workflow(testWorkflow).cronExpression("")
                .scheduleType(ScheduleType.ONCE).scheduledAt(FIXED_NOW.plus(3, ChronoUnit.HOURS)).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(inside, after));

        UpcomingScheduleResponse result = calculator.getRemainingToday();

        assertThat(result.schedules()).extracting(UpcomingScheduleResponse.UpcomingRun::scheduleId)
                .containsExactly("once-after");
    }

    @Test
    void pauseInThePast_orUnparseable_changesNothing_andLogsNothing() {
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(once(FIXED_NOW.plus(1, ChronoUnit.HOURS))));

        schedulerConfig.setSkipUntil(FIXED_NOW.minus(1, ChronoUnit.HOURS).toString());
        UpcomingScheduleResponse past = calculator.getRemainingToday();
        schedulerConfig.setSkipUntil("next tuesday");
        UpcomingScheduleResponse garbage = calculator.getRemainingToday();

        assertThat(past.count()).isEqualTo(1);
        assertThat(past.pausedUntil()).isNull();
        assertThat(garbage.count()).isEqualTo(1);
        assertThat(garbage.pausedUntil()).isNull();
        assertThat(calculator.pausedUntil()).isEmpty();
        // A read path writes no activity-log rows; warning about the bad value is the tick's job.
        verifyNoInteractions(activityLogService);
    }

    @Test
    void getNextRuns_duringPause_startAfterIt() {
        Instant pauseEnd = FIXED_NOW.plus(5, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES);
        schedulerConfig.setSkipUntil(pauseEnd.toString());

        List<Instant> runs = calculator.getNextRuns("0 0 * * * *", 3);

        assertThat(runs).hasSize(3).allMatch(run -> !run.isBefore(pauseEnd));
        assertThat(runs.get(0)).isBefore(pauseEnd.plus(1, ChronoUnit.HOURS));
    }

    @Test
    void findWithNextRunBetween_withoutPause_isTheStoredRange() {
        // Moved from SchedulerServiceTest.findSchedulesInRange_delegatesToRepository.
        Instant from = FIXED_NOW;
        Instant to = FIXED_NOW.plus(1, ChronoUnit.DAYS);
        ScheduledWorkflow stored = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).build();
        when(scheduledRepository.findSchedulesInRange(from, to)).thenReturn(List.of(stored));

        assertThat(calculator.findWithNextRunBetween(from, to)).containsExactly(stored);
    }

    @Test
    void findWithNextRunBetween_duringPause_placesSchedulesAtTheRunThatReallyComes() {
        Instant pauseEnd = FIXED_NOW.plus(2, ChronoUnit.DAYS);
        schedulerConfig.setSkipUntil(pauseEnd.toString());
        ScheduledWorkflow heldCron = ScheduledWorkflow.builder().id("held-cron").workflow(testWorkflow)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).build();
        ScheduledWorkflow heldOnce = ScheduledWorkflow.builder().id("held-once").workflow(testWorkflow)
                .cronExpression("").scheduleType(ScheduleType.ONCE).enabled(true)
                .scheduledAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).nextRunAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).build();
        ScheduledWorkflow later = ScheduledWorkflow.builder().id("later").workflow(testWorkflow)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(FIXED_NOW.plus(3, ChronoUnit.DAYS)).build();
        // The repository answers by the stored next run: everything stored up to "to".
        when(scheduledRepository.findSchedulesInRange(Instant.EPOCH, FIXED_NOW.plus(1, ChronoUnit.DAYS)))
                .thenReturn(List.of(heldCron, heldOnce));
        when(scheduledRepository.findSchedulesInRange(Instant.EPOCH, FIXED_NOW.plus(4, ChronoUnit.DAYS)))
                .thenReturn(List.of(heldCron, heldOnce, later));

        // The next day: the held runs never happen there, the one-off expires.
        assertThat(calculator.findWithNextRunBetween(FIXED_NOW, FIXED_NOW.plus(1, ChronoUnit.DAYS))).isEmpty();
        // The days after the pause: the held CRON schedule shows up there, next to the later one.
        assertThat(calculator.findWithNextRunBetween(FIXED_NOW.plus(1, ChronoUnit.DAYS), FIXED_NOW.plus(4, ChronoUnit.DAYS)))
                .containsExactly(heldCron, later);
        assertThat(calculator.nextRun(heldCron)).isAfterOrEqualTo(pauseEnd).isBefore(pauseEnd.plus(1, ChronoUnit.HOURS));
        assertThat(calculator.nextRun(heldOnce)).isNull();
        assertThat(calculator.nextRun(later)).isEqualTo(FIXED_NOW.plus(3, ChronoUnit.DAYS));
    }

    // --- maintenance switch (TODO 7704b1e9): nothing starts, so nothing is upcoming ---

    @Test
    void remainingTodayAndWeek_underMaintenance_listNoRun_andSaySo() {
        when(maintenanceMode.isEnabled()).thenReturn(true);

        UpcomingScheduleResponse today = calculator.getRemainingToday();
        UpcomingScheduleResponse week = calculator.getRemainingThisWeek();

        for (UpcomingScheduleResponse result : List.of(today, week)) {
            assertThat(result.maintenance()).isTrue();
            assertThat(result.count()).isZero();
            assertThat(result.schedules()).isEmpty();
            assertThat(result.pausedUntil()).isNull();
        }
        // No schedule is even looked at: whatever is stored, the tick starts none of it.
        verifyNoInteractions(scheduledRepository);
    }

    @Test
    void remainingThisWeek_withoutMaintenance_saysNothingAboutIt() {
        ScheduledWorkflow everyMinute = ScheduledWorkflow.builder().id("s").workflow(testWorkflow)
                .cronExpression("0 * * * * *").scheduleType(ScheduleType.CRON).enabled(true).build();
        when(scheduledRepository.findByEnabledTrue()).thenReturn(List.of(everyMinute));

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

        assertThat(result.maintenance()).isFalse();
        assertThat(result.count()).isPositive();
    }

    @Test
    void maintenanceAndSkipUntilTogether_nameBoth() {
        Instant pauseEnd = FIXED_NOW.plus(3, ChronoUnit.HOURS);
        schedulerConfig.setSkipUntil(pauseEnd.toString());
        when(maintenanceMode.isEnabled()).thenReturn(true);

        UpcomingScheduleResponse result = calculator.getRemainingThisWeek();

        assertThat(result.maintenance()).isTrue();
        assertThat(result.pausedUntil()).isEqualTo(pauseEnd);
        assertThat(result.schedules()).isEmpty();
    }

    @Test
    void findWithNextRunBetween_underMaintenance_placesNoSchedule_andNextRunIsNone() {
        when(maintenanceMode.isEnabled()).thenReturn(true);
        ScheduledWorkflow cron = ScheduledWorkflow.builder().id("cron").workflow(testWorkflow)
                .cronExpression("0 0 * * * *").scheduleType(ScheduleType.CRON).enabled(true)
                .nextRunAt(FIXED_NOW.plus(1, ChronoUnit.HOURS)).build();
        ScheduledWorkflow oneOff = ScheduledWorkflow.builder().id("once").workflow(testWorkflow)
                .cronExpression("").scheduleType(ScheduleType.ONCE).enabled(true)
                .scheduledAt(FIXED_NOW.plus(2, ChronoUnit.HOURS)).nextRunAt(FIXED_NOW.plus(2, ChronoUnit.HOURS)).build();
        when(scheduledRepository.findSchedulesInRange(Instant.EPOCH, FIXED_NOW.plus(1, ChronoUnit.DAYS)))
                .thenReturn(List.of(cron, oneOff));

        assertThat(calculator.findWithNextRunBetween(FIXED_NOW, FIXED_NOW.plus(1, ChronoUnit.DAYS))).isEmpty();
        assertThat(calculator.nextRun(cron)).isNull();
        assertThat(calculator.nextRun(oneOff)).isNull();
        assertThat(cron.getNextRunAt()).as("a view never writes").isEqualTo(FIXED_NOW.plus(1, ChronoUnit.HOURS));
    }
}
