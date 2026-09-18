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

    /** Emit the {@code Server-Timing} response header. Startup-only, see class comment. */
    private boolean serverTimingEnabled = false;
}
