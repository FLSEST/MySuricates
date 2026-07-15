package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime-configurable settings for the TODO ghost watchdog (orphaned IN_PROGRESS reset).
 * Mutated at runtime by ExecutionSettingsService
 * (keys: todo-watchdog-enabled, todo-watchdog-stale-hours).
 *
 * Scope is machine-sourced todos (AI/WORKFLOW) only: those go stale when a CLI session
 * dies without the closing status PATCH. Human todos (source USER) may legitimately sit
 * IN_PROGRESS for days and are never touched.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.todo-watchdog")
@Getter
@Setter
public class TodoGhostWatchdogConfig {
    /** When false, the watchdog polls but never mutates. Default ON — resets are low-stakes (OPEN is reversible). */
    private boolean enabled = true;
    /** An AI/WORKFLOW todo counts as orphaned after this many hours without activity (updatedAt). */
    private int staleHours = 24;
    /** Upper bound on how many todos are reset per poll cycle. */
    private int maxPerRun = 100;
    /** Poll interval in milliseconds (read once at startup by @Scheduled). */
    private long pollMs = 300_000;
}
