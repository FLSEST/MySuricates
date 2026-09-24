package com.automation.service.scheduler;

import java.time.Instant;
import java.util.Optional;

/**
 * What holds the scheduler, as the read views see it — both pauses can be on at once. Read once per
 * view ({@link ScheduleSkipPolicy#activePause}) and handed to the one rule that turns a stored next
 * run into the run that really comes ({@link ScheduleSkipPolicy#effectiveNextRun}).
 *
 * <ul>
 *   <li>{@code maintenance} — the switch {@code scheduler.maintenance-mode} (pause button of the
 *       schedules page): nothing starts while it is on, and it has no end; once it is switched off,
 *       every overdue schedule starts once. A view knows no next run for a schedule it holds
 *       (TODO 7704b1e9).</li>
 *   <li>{@code skipUntil} — an active {@code automation.scheduler.skip-until} window: occurrences
 *       before its end are skipped, the next run is the first occurrence after it (TODO a776fd2a).</li>
 * </ul>
 */
public record SchedulerPause(boolean maintenance, Optional<Instant> skipUntil) {

    /** True while either pause is on. */
    public boolean active() {
        return maintenance || skipUntil.isPresent();
    }
}
