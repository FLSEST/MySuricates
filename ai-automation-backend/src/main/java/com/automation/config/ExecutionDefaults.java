package com.automation.config;

/**
 * Central constants for execution timeouts, limits, and truncation lengths.
 * Replaces magic numbers scattered across block executors and services.
 */
public final class ExecutionDefaults {

    private ExecutionDefaults() {}

    // ── Shell / Block Timeouts ─────────────────────────────────

    /** Default timeout for shell commands and block execution (seconds). */
    public static final int SHELL_TIMEOUT_SECONDS = 300;

    /** Default timeout for approval and human-input blocks (minutes). */
    public static final int INTERACTION_TIMEOUT_MINUTES = 30;

    /** Max wait time for sub-workflow completion (minutes). */
    public static final int SUB_WORKFLOW_MAX_WAIT_MINUTES = 30;

    // ── Agentic Block Defaults ─────────────────────────────────

    /** Default max iterations for agentic blocks. */
    public static final int AGENTIC_MAX_ITERATIONS = 10;

    /** Default per-iteration timeout (seconds). */
    public static final int AGENTIC_ITERATION_TIMEOUT_SECONDS = 300;

    /** Default total timeout for agentic blocks (seconds, 30 min). */
    public static final int AGENTIC_TOTAL_TIMEOUT_SECONDS = 1800;

    /** Default token budget (input + output across all iterations). */
    public static final int AGENTIC_MAX_TOTAL_TOKENS = 200_000;

    /** Abort after N identical consecutive actions. */
    public static final int AGENTIC_MAX_REPEATED_ACTIONS = 3;

    // ── Polling Intervals ──────────────────────────────────────

    /** Polling interval for workflow/chain completion checks (ms). */
    public static final long POLLING_INTERVAL_MS = 2000;

    /** Health check polling interval (ms). */
    public static final long HEALTH_CHECK_INTERVAL_MS = 2000;

    // ── Truncation Lengths ─────────────────────────────────────

    /** Truncation length for reasoning output and action results. */
    public static final int TRUNCATE_LONG = 2000;

    /** Truncation length for action descriptions and rationale summaries. */
    public static final int TRUNCATE_MEDIUM = 500;

    /** Truncation length for brief summaries (rationale, action in logs). */
    public static final int TRUNCATE_SHORT = 200;

    /** Truncation length for observation context passed back to the model. */
    public static final int TRUNCATE_OBSERVATION = 4000;

    /** Truncation length for conversation history compression. */
    public static final int TRUNCATE_HISTORY = 150;

    // ── Network Timeouts ───────────────────────────────────────

    /** Default HTTP connect timeout for internal API calls (ms). */
    public static final int HTTP_CONNECT_TIMEOUT_MS = 3000;

    /** Default HTTP read timeout for internal API calls (ms). */
    public static final int HTTP_READ_TIMEOUT_MS = 3000;

    /** HTTP connect timeout for sync operations (ms). */
    public static final int SYNC_CONNECT_TIMEOUT_MS = 10_000;

    /** HTTP read timeout for sync operations (ms). */
    public static final int SYNC_READ_TIMEOUT_MS = 30_000;

    // ── Retention ──────────────────────────────────────────────

    /** Activity log retention period (days). */
    public static final int LOG_RETENTION_DAYS = 30;

    // ── Container ──────────────────────────────────────────────

    /** Docker container stop timeout (seconds). */
    public static final int DOCKER_STOP_TIMEOUT_SECONDS = 10;

    /** Health check timeout for staging/test instances (seconds). */
    public static final int INSTANCE_HEALTH_CHECK_TIMEOUT_SECONDS = 60;
}
