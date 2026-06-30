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
     * Global default for catch-up behaviour after a system resume. When true, all due schedules
     * are executed once on resume (catch-up) unless individually overridden. When false, per-schedule
     * {@code catchUpOnResume} controls the behaviour. Configurable at runtime via the Settings UI.
     */
    private boolean catchUpOnResume = true;
}
