package com.automation.service.scheduler;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Shared in-memory signal recording when the application last detected a system resume after a
 * suspend/freeze (e.g. laptop sleep). Written by {@code SchedulerService} (which detects the
 * resume via a large gap between scheduler ticks) and read by {@code GhostRunWatchdogService} to
 * label runs that were orphaned across the suspend as suspend-interrupted rather than generic
 * ghost runs.
 *
 * <p>Not persisted: a real application restart already handles in-flight runs via startup
 * reconciliation ({@code cleanupZombieRuns}); this only matters within a single JVM lifetime.
 */
@Component
public class SuspendState {

    private volatile Instant lastResumeAt;
    private volatile long lastGapMillis;

    public void recordResume(Instant at, long gapMillis) {
        this.lastResumeAt = at;
        this.lastGapMillis = gapMillis;
    }

    public Instant getLastResumeAt() {
        return lastResumeAt;
    }

    public long getLastGapMillis() {
        return lastGapMillis;
    }

    /** True if a resume was detected within {@code window} before {@code now}. */
    public boolean resumedRecently(Instant now, Duration window) {
        Instant resume = lastResumeAt;
        return resume != null && !resume.isBefore(now.minus(window));
    }
}
