package com.automation.service.system;

import java.util.Map;

/**
 * Static catalogue of the runtime-tunable execution settings: canonical kebab-case keys with
 * their numeric bounds, the camelCase aliases the frontend sends, and the shared
 * alias-resolution / bounds-check logic.
 *
 * <p>Extracted from {@code ExecutionSettingsService} (Audit-TODO a0dcc7d7). Does <b>not</b>
 * persist or apply anything — it only knows which keys exist and what values are legal.
 */
final class ExecutionSettingKeys {

    private ExecutionSettingKeys() {
    }

    static final Map<String, long[]> BOUNDS = Map.ofEntries(
            Map.entry("execution-max-concurrent",          new long[]{1, 50}),
            Map.entry("claude-max-concurrent-calls",       new long[]{1, 20}),
            Map.entry("claude-reserved-for-interactive",   new long[]{0, 10}),
            Map.entry("claude-timeout-seconds",            new long[]{30, 3600}),
            Map.entry("claude-inter-call-delay-ms",        new long[]{0, 30000}),
            Map.entry("chain-workflow-timeout-minutes",    new long[]{5, 1440}),
            Map.entry("rate-limit-enabled",                new long[]{0, 1}),
            Map.entry("rate-limit-requests-per-minute",    new long[]{10, 10000}),
            Map.entry("assistant-chat-timeout-seconds",    new long[]{30, 900}),
            Map.entry("task-command-timeout-seconds",      new long[]{10, 3600}),
            Map.entry("allow-extra-mounts",                new long[]{0, 1}),
            Map.entry("todo-hygiene-enabled",              new long[]{0, 1}),
            Map.entry("todo-stale-days",                   new long[]{7, 365}),
            Map.entry("todo-hygiene-max-per-run",          new long[]{10, 2000}),
            Map.entry("run-retention-days",                new long[]{0, 730}),
            Map.entry("run-retention-max-per-night",       new long[]{10, 5000}),
            Map.entry("todo-watchdog-enabled",             new long[]{0, 1}),
            Map.entry("todo-watchdog-stale-hours",         new long[]{1, 720}),
            Map.entry("assistant-max-actions-per-hour",    new long[]{1, 500}),
            Map.entry("scheduler-catch-up-on-resume",      new long[]{0, 1}),
            Map.entry("scheduler-resume-grace-minutes",    new long[]{1, 60}),
            Map.entry("summarization-timeout-seconds",     new long[]{30, 600}),
            Map.entry("factory-timeout-seconds",           new long[]{60, 3600}),
            Map.entry("wish-total-timeout-minutes",        new long[]{5, 1440}),
            Map.entry("wish-workflow-wait-minutes",        new long[]{1, 120}),
            Map.entry("wish-default-shell-timeout-seconds",             new long[]{30, 3600}),
            Map.entry("wish-default-agentic-total-timeout-seconds",     new long[]{60, 86400}),
            Map.entry("model-catalog-ttl-seconds",         new long[]{30, 86400}),
            Map.entry("compress-ai-max-input-chars",       new long[]{1000, 400000})
    );

    // Frontend sends camelCase keys, backend uses kebab-case internally
    static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("executionMaxConcurrent",          "execution-max-concurrent"),
            Map.entry("claudeMaxConcurrentCalls",        "claude-max-concurrent-calls"),
            Map.entry("claudeReservedForInteractive",    "claude-reserved-for-interactive"),
            Map.entry("claudeTimeoutSeconds",            "claude-timeout-seconds"),
            Map.entry("claudeInterCallDelayMs",          "claude-inter-call-delay-ms"),
            Map.entry("chainWorkflowTimeoutMinutes",     "chain-workflow-timeout-minutes"),
            Map.entry("rateLimitEnabled",                "rate-limit-enabled"),
            Map.entry("rateLimitRequestsPerMinute",      "rate-limit-requests-per-minute"),
            Map.entry("assistantChatTimeoutSeconds",     "assistant-chat-timeout-seconds"),
            Map.entry("taskCommandTimeoutSeconds",       "task-command-timeout-seconds"),
            Map.entry("allowExtraMounts",                "allow-extra-mounts"),
            Map.entry("todoHygieneEnabled",              "todo-hygiene-enabled"),
            Map.entry("todoStaleDays",                   "todo-stale-days"),
            Map.entry("todoHygieneMaxPerRun",            "todo-hygiene-max-per-run"),
            Map.entry("runRetentionDays",                "run-retention-days"),
            Map.entry("runRetentionMaxPerNight",         "run-retention-max-per-night"),
            Map.entry("todoWatchdogEnabled",             "todo-watchdog-enabled"),
            Map.entry("todoWatchdogStaleHours",          "todo-watchdog-stale-hours"),
            Map.entry("assistantMaxActionsPerHour",      "assistant-max-actions-per-hour"),
            Map.entry("schedulerCatchUpOnResume",        "scheduler-catch-up-on-resume"),
            Map.entry("schedulerResumeGraceMinutes",     "scheduler-resume-grace-minutes"),
            Map.entry("summarizationTimeoutSeconds",     "summarization-timeout-seconds"),
            Map.entry("factoryTimeoutSeconds",           "factory-timeout-seconds"),
            Map.entry("wishTotalTimeoutMinutes",                    "wish-total-timeout-minutes"),
            Map.entry("wishWorkflowWaitMinutes",                    "wish-workflow-wait-minutes"),
            Map.entry("wishDefaultShellTimeoutSeconds",             "wish-default-shell-timeout-seconds"),
            Map.entry("wishDefaultAgenticTotalTimeoutSeconds",      "wish-default-agentic-total-timeout-seconds"),
            Map.entry("modelCatalogTtlSeconds",          "model-catalog-ttl-seconds"),
            Map.entry("compressAiMaxInputChars",         "compress-ai-max-input-chars")
    );

    /**
     * Maps a camelCase alias (or an already canonical key) to its canonical kebab-case key.
     *
     * @throws IllegalArgumentException if the key is unknown; the message quotes the key as given
     */
    static String resolve(String key) {
        String resolvedKey = KEY_ALIASES.getOrDefault(key, key);
        if (!BOUNDS.containsKey(resolvedKey)) {
            throw new IllegalArgumentException("Unknown execution setting: " + key);
        }
        return resolvedKey;
    }

    /**
     * Parses {@code value} and checks it against the bounds of the canonical {@code key}.
     *
     * @throws IllegalArgumentException if the value is not a number or outside the bounds
     */
    static long parseWithinBounds(String key, String value) {
        long[] bounds = BOUNDS.get(key);
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Value must be a number: " + value);
        }
        if (parsed < bounds[0] || parsed > bounds[1]) {
            throw new IllegalArgumentException(
                    key + " must be between " + bounds[0] + " and " + bounds[1] + ", got " + parsed);
        }
        return parsed;
    }

    /** Bounds of a canonical key as {@code {min, max}}. */
    static long[] bounds(String key) {
        return BOUNDS.get(key);
    }
}
