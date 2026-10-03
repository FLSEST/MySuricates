package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * The end conditions of a CRON schedule — {@code endDate} and {@code maxRuns} — in one place: the
 * tick ends a schedule by them, the skip policy must not move a schedule past them, and create,
 * update and enable refuse a schedule that would look active although it has no run left
 * (TODO 1e801952). Before, such a schedule ended silently when it fell due — or, with
 * {@code maxRuns} lowered under {@code runCount} by an update, started once more.
 */
final class ScheduleEndConditions {

    /** How far a one-off may lie behind now and still count as "now" — one tick (TODO 1e801952). */
    static final Duration ONCE_TOLERANCE = Duration.ofSeconds(60);

    private ScheduleEndConditions() {
    }

    /**
     * True when the end date lies at or before {@code occurrence}: the schedule is over by then
     * ("disabled when now >= endDate"), so the occurrence does not run. Measured on the occurrence,
     * not on the moment the tick picks it up, so the last run before the end date does not depend
     * on where the 60-s tick falls.
     */
    static boolean reachesEndDate(ScheduledWorkflow scheduled, Instant occurrence) {
        return scheduled.getEndDate() != null && occurrence != null
                && !occurrence.isBefore(scheduled.getEndDate());
    }

    /** True when a {@code maxRuns} limit is set and used up. */
    static boolean maxRunsReached(ScheduledWorkflow scheduled) {
        return scheduled.getMaxRuns() > 0 && scheduled.getRunCount() >= scheduled.getMaxRuns();
    }

    /**
     * Refuses (400) an enabled CRON schedule whose next run was just computed and came out empty: a
     * valid expression without a future occurrence (30 February). Checked only where the next run is
     * computed — create, enable, a new expression — so an edit of other fields stays possible.
     */
    static void requireNextRun(ScheduledWorkflow scheduled) {
        if (scheduled.isEnabled() && scheduled.getScheduleType() == ScheduleType.CRON
                && scheduled.getNextRunAt() == null) {
            throw new LocalizedArgumentException(ErrorMessage.SCHEDULE_NO_NEXT_RUN,
                    Map.of("expression", String.valueOf(scheduled.getCronExpression())));
        }
    }

    /**
     * Refuses (400) an enabled schedule that has no run left: a CRON schedule whose runs are used up
     * or whose next run lies at or after its end date; a
     * ONCE schedule whose time lies more than {@link #ONCE_TOLERANCE} before {@code now} — enabled, it
     * would start at once. A disabled schedule has no next run and passes — enabling it checks again. DELAYED is not checked: its
     * time is computed from now.
     */
    static void requireRunLeft(ScheduledWorkflow scheduled, Instant now) {
        if (!scheduled.isEnabled()) {
            return;
        }
        if (scheduled.getScheduleType() == ScheduleType.ONCE) {
            // A time in the running minute is not the past: it starts with the next tick.
            if (scheduled.getNextRunAt() != null && scheduled.getNextRunAt().isBefore(now.minus(ONCE_TOLERANCE))) {
                throw new LocalizedArgumentException(ErrorMessage.SCHEDULE_ONCE_IN_PAST,
                        Map.of("scheduledAt", scheduled.getNextRunAt().toString()));
            }
            return;
        }
        if (scheduled.getScheduleType() != ScheduleType.CRON) {
            return;
        }
        if (maxRunsReached(scheduled)) {
            throw new LocalizedArgumentException(ErrorMessage.SCHEDULE_MAX_RUNS_REACHED, Map.of(
                    "maxRuns", String.valueOf(scheduled.getMaxRuns()),
                    "runCount", String.valueOf(scheduled.getRunCount())));
        }
        if (reachesEndDate(scheduled, scheduled.getNextRunAt())) {
            throw new LocalizedArgumentException(ErrorMessage.SCHEDULE_NEXT_RUN_AFTER_END_DATE, Map.of(
                    "nextRun", scheduled.getNextRunAt().toString(),
                    "endDate", scheduled.getEndDate().toString()));
        }
    }
}
