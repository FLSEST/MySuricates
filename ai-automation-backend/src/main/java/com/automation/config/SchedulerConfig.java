package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.scheduler")
@Getter
@Setter
public class SchedulerConfig {

    private String skipUntil = "";

    /**
     * Minimum gap between two scheduler ticks (minutes) that is interpreted as a system resume
     * after suspend/freeze. On such a tick the backlog of due schedules is handled by the
     * catch-up policy instead of being fired all at once. 0 disables resume detection.
     */
    private long resumeGraceMinutes = 5;

    /**
     * Global switch for occurrences missed during a suspend or while the backend was stopped.
     * True (default): every missed schedule is caught up once, one after another (CatchUpQueue).
     * False: only schedules with their own {@code catchUpOnResume} are caught up, all others skip
     * forward. The per-schedule flag can only add catch-up, never switch it off. An active
     * {@link #skipUntil} window wins over both. Configurable at runtime via the Settings UI
     * ({@code scheduler-catch-up-on-resume}).
     */
    private boolean catchUpOnResume = true;
}
