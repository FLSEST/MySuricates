package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleEndConditionsTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private static ScheduledWorkflow.ScheduledWorkflowBuilder cron() {
        return ScheduledWorkflow.builder().scheduleType(ScheduleType.CRON).cronExpression("0 0 6 * * *").enabled(true);
    }

    @Test
    void reachesEndDate_atOrAfterTheEndDate_notBefore() {
        ScheduledWorkflow s = cron().endDate(NOW).build();

        assertThat(ScheduleEndConditions.reachesEndDate(s, NOW.minusSeconds(1))).isFalse();
        assertThat(ScheduleEndConditions.reachesEndDate(s, NOW)).isTrue();
        assertThat(ScheduleEndConditions.reachesEndDate(s, NOW.plusSeconds(1))).isTrue();
    }

    @Test
    void reachesEndDate_isFalseWithoutEndDateOrOccurrence() {
        assertThat(ScheduleEndConditions.reachesEndDate(cron().build(), NOW)).isFalse();
        assertThat(ScheduleEndConditions.reachesEndDate(cron().endDate(NOW).build(), null)).isFalse();
    }

    @Test
    void maxRunsReached_onlyWithALimitThatIsUsedUp() {
        assertThat(ScheduleEndConditions.maxRunsReached(cron().maxRuns(0).runCount(50).build())).isFalse();
        assertThat(ScheduleEndConditions.maxRunsReached(cron().maxRuns(3).runCount(2).build())).isFalse();
        assertThat(ScheduleEndConditions.maxRunsReached(cron().maxRuns(3).runCount(3).build())).isTrue();
        assertThat(ScheduleEndConditions.maxRunsReached(cron().maxRuns(3).runCount(5).build()))
                .as("a limit lowered below the run count").isTrue();
    }

    @Test
    void requireNextRun_refusesAnEnabledCronScheduleWithoutNextRun() {
        ScheduledWorkflow s = cron().cronExpression("0 0 0 30 2 *").nextRunAt(null).build();

        assertThatThrownBy(() -> ScheduleEndConditions.requireNextRun(s))
                .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.SCHEDULE_NO_NEXT_RUN);
                    assertThat(e.params()).isEqualTo(Map.of("expression", "0 0 0 30 2 *"));
                });
    }

    @Test
    void requireNextRun_passesWithNextRun_whenDisabled_andForOtherTypes() {
        assertThatCode(() -> ScheduleEndConditions.requireNextRun(cron().nextRunAt(NOW).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireNextRun(cron().enabled(false).build()))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireNextRun(
                ScheduledWorkflow.builder().scheduleType(ScheduleType.ONCE).build())).doesNotThrowAnyException();
    }

    @Test
    void requireRunLeft_ignoresDisabledAndDelayedSchedules() {
        ScheduledWorkflow disabled = cron().enabled(false).maxRuns(1).runCount(1).build();
        ScheduledWorkflow delayed = ScheduledWorkflow.builder().scheduleType(ScheduleType.DELAYED)
                .nextRunAt(NOW.minusSeconds(3600)).maxRuns(1).runCount(1).build();

        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(disabled, NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(delayed, NOW)).doesNotThrowAnyException();
    }

    @Test
    void requireRunLeft_onceInThePastBeyondTheToleranceIsRefused() {
        Instant past = NOW.minus(ScheduleEndConditions.ONCE_TOLERANCE).minusSeconds(1);
        ScheduledWorkflow s = ScheduledWorkflow.builder().scheduleType(ScheduleType.ONCE).nextRunAt(past).build();

        assertThatThrownBy(() -> ScheduleEndConditions.requireRunLeft(s, NOW))
                .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.SCHEDULE_ONCE_IN_PAST);
                    assertThat(e.params()).isEqualTo(Map.of("scheduledAt", past.toString()));
                });
    }

    @Test
    void requireRunLeft_onceWithinTheToleranceOrInTheFutureOrWithoutTimePasses() {
        ScheduledWorkflow.ScheduledWorkflowBuilder once = ScheduledWorkflow.builder().scheduleType(ScheduleType.ONCE);

        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(
                once.nextRunAt(NOW.minus(ScheduleEndConditions.ONCE_TOLERANCE)).build(), NOW)).doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(once.nextRunAt(NOW.plusSeconds(60)).build(), NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(once.nextRunAt(null).build(), NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(once.nextRunAt(NOW.plusSeconds(60)).maxRuns(1)
                .runCount(1).build(), NOW)).as("maxRuns is a CRON condition").doesNotThrowAnyException();
    }

    @Test
    void requireRunLeft_cronWithUsedUpRunsIsRefused() {
        ScheduledWorkflow s = cron().nextRunAt(NOW.plusSeconds(3600)).maxRuns(2).runCount(2).build();

        assertThatThrownBy(() -> ScheduleEndConditions.requireRunLeft(s, NOW))
                .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.SCHEDULE_MAX_RUNS_REACHED);
                    assertThat(e.params()).isEqualTo(Map.of("maxRuns", "2", "runCount", "2"));
                });
    }

    @Test
    void requireRunLeft_cronWhoseNextRunIsNotBeforeTheEndDateIsRefused() {
        Instant next = NOW.plusSeconds(3600);
        ScheduledWorkflow s = cron().nextRunAt(next).endDate(next).build();

        assertThatThrownBy(() -> ScheduleEndConditions.requireRunLeft(s, NOW))
                .isInstanceOfSatisfying(LocalizedArgumentException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.SCHEDULE_NEXT_RUN_AFTER_END_DATE);
                    assertThat(e.params()).isEqualTo(Map.of("nextRun", next.toString(), "endDate", next.toString()));
                });
    }

    @Test
    void requireRunLeft_cronWithARunLeftPasses() {
        Instant next = NOW.plusSeconds(3600);

        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(
                cron().nextRunAt(next).endDate(next.plusSeconds(1)).maxRuns(3).runCount(2).build(), NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> ScheduleEndConditions.requireRunLeft(cron().nextRunAt(null).endDate(NOW).build(), NOW))
                .as("no next run is requireNextRun's business").doesNotThrowAnyException();
    }
}
