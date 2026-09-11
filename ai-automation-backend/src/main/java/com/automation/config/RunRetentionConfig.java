package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime-configurable retention for finished workflow runs (COMPLETED, COMPLETED_WITH_ERRORS,
 * FAILED, CANCELLED) and their step results — the main driver of SQLite growth. Mutated at
 * runtime by ExecutionSettingsService (keys: run-retention-days, run-retention-max-per-night).
 * <p>
 * Runs referenced by an audit report are never touched; INTERRUPTED runs have their own,
 * older rule in RunCleanupService.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.run.retention")
@Getter
@Setter
public class RunRetentionConfig {
    /** Finished runs older than this many days are deleted. 0 disables the job. */
    private int retentionDays = 90;
    /** Upper bound on runs deleted per nightly pass (one short transaction each, pool-size=1). */
    private int maxPerNight = 200;
}
