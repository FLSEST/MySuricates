package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime-configurable settings for the TODO hygiene job (stale auto-todo archival)
 * and the cockpit's staleness threshold. Mutated at runtime by ExecutionSettingsService
 * (keys: todo-hygiene-enabled, todo-stale-days, todo-hygiene-max-per-run).
 *
 * Auto-archival defaults to OFF — the primary path is the cockpit's one-click
 * "Verstaubtes archivieren" (human-in-the-loop). The scheduled job stays dormant
 * until enabled, to avoid silently mass-mutating existing todos on first deploy.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.todo.hygiene")
@Getter
@Setter
public class TodoHygieneConfig {
    /** When true, the scheduled job archives stale auto-todos. Default OFF. */
    private boolean enabled = false;
    /** A todo counts as stale after this many days without activity (updatedAt). */
    private int staleDays = 30;
    /** Upper bound on how many todos the scheduled job archives per run. */
    private int maxPerRun = 200;
}
