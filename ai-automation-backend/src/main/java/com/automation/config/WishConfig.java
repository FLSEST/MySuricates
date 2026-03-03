package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "automation.wish")
@Getter
@Setter
public class WishConfig {

    /** Overall wish fulfillment timeout in minutes. */
    private int totalTimeoutMinutes = 120;

    /** Max wait time for a single workflow/chain run to complete. */
    private int workflowWaitMinutes = 30;

    /** Enable the stuck-state watchdog. */
    private boolean watchdogEnabled = true;

    /** Watchdog check interval in milliseconds. */
    private long watchdogIntervalMs = 120_000;

    /** Minutes after which a wish in an active state is considered stale. */
    private int watchdogStaleMinutes = 45;

    /** Default timeout_seconds for generated shell blocks. */
    private int defaultShellTimeoutSeconds = 600;

    /** Default timeout_seconds for generated agentic blocks. */
    private int defaultAgenticTimeoutSeconds = 900;

    /** Default iteration_timeout_seconds for generated agentic blocks. */
    private int defaultAgenticIterationTimeoutSeconds = 900;

    /** Default total_timeout_seconds for generated agentic blocks. */
    private int defaultAgenticTotalTimeoutSeconds = 3600;
}
