package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Per-request cost measurement: duration, connection wait, SQL statements and response bytes,
 * aggregated per route. Mutated at runtime by ExecutionSettingsService (keys:
 * request-metrics-enabled, request-metrics-retention-days, request-metrics-slow-request-ms).
 *
 * <p>{@link #serverTimingEnabled} is deliberately <b>not</b> runtime-switchable: the header
 * exposes internal timings, so whether it is emitted is a property of the deployment, not
 * something an API call may flip. It is on in the dev profile and off everywhere else.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.request-metrics")
@Getter
@Setter
public class RequestMetricsConfig {

    /** Measure at all. When false the filter passes the request through untouched. */
    private boolean enabled = true;

    /** Daily snapshots older than this many days are deleted. 0 disables the cleanup. */
    private int retentionDays = 90;

    /** A single request slower than this lands in the ActivityLog. 0 disables that. */
    private long slowRequestMs = 2000;

    /**
     * Emit the {@code Server-Timing} response header. Startup-only, see class comment.
     *
     * <p>Default on, off in the stable and prod profiles. The dev instance runs with no profile
     * set at all ({@code No active profile set, falling back to "default"}), so tying this to a
     * {@code dev} profile would have switched it off on the one instance it is meant for.
     */
    private boolean serverTimingEnabled = true;

    /** Report drift at all. */
    private boolean driftDetectionEnabled = true;

    /** A route is drifting when today's p95 exceeds its baseline by this factor. */
    private double driftFactor = 2.0;

    /**
     * Calls a route needs today before it is judged at all. Without this a single cold request
     * after a restart — JIT not warm, caches empty — reads as a catastrophic regression.
     */
    private int driftMinCalls = 20;

    /** How many stored days form the baseline a route is compared against. */
    private int baselineDays = 7;
}
