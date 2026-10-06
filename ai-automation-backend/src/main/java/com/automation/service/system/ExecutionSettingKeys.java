package com.automation.service.system;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;

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

    /**
     * Bounds of a key. {@code decimal} decides whether a fraction is allowed and how the
     * error message writes the numbers (integer {@code 1 und 50}, fraction {@code 0.05 und 0.9}).
     *
     * <p>One shared type instead of two maps (TODO 4218fcaa): a new key is entered in only
     * one place, and both gate tests check the same source.
     */
    record Bound(double min, double max, boolean decimal) {

        /** Key with an integer value. */
        static Bound whole(long min, long max) {
            return new Bound(min, max, false);
        }

        /** Key whose value may be a fraction. */
        static Bound fraction(double min, double max) {
            return new Bound(min, max, true);
        }

        /**
         * Parses the value in the number format of the key.
         *
         * @throws IllegalArgumentException if the value is not a matching number
         */
        double parse(String value) {
            try {
                return decimal ? Double.parseDouble(value) : Long.parseLong(value);
            } catch (NumberFormatException | NullPointerException e) {
                throw new IllegalArgumentException("Value must be a number: " + value);
            }
        }

        boolean contains(double value) {
            return value >= min && value <= max;
        }

        /** Writes a number the way the error message of this key shows it. */
        String format(double value) {
            return decimal ? String.valueOf(value) : String.valueOf((long) value);
        }
    }

    static final Map<String, Bound> BOUNDS = Map.ofEntries(
            Map.entry("execution-max-concurrent",          Bound.whole(1, 50)),
            Map.entry("claude-max-concurrent-calls",       Bound.whole(1, 20)),
            Map.entry("claude-reserved-for-interactive",   Bound.whole(0, 10)),
            // AI CLI limits per provider and across all providers (TODO bd5df8d7)
            Map.entry("pi-max-concurrent-calls",           Bound.whole(1, 20)),
            Map.entry("pi-reserved-for-interactive",       Bound.whole(0, 10)),
            Map.entry("codex-max-concurrent-calls",        Bound.whole(1, 20)),
            Map.entry("codex-reserved-for-interactive",    Bound.whole(0, 10)),
            Map.entry("ai-cli-max-concurrent-calls",       Bound.whole(1, 50)),
            Map.entry("ai-cli-reserved-for-interactive",   Bound.whole(0, 10)),
            Map.entry("claude-timeout-seconds",            Bound.whole(30, 3600)),
            Map.entry("claude-inter-call-delay-ms",        Bound.whole(0, 30000)),
            Map.entry("chain-workflow-timeout-minutes",    Bound.whole(5, 1440)),
            Map.entry("rate-limit-enabled",                Bound.whole(0, 1)),
            Map.entry("rate-limit-requests-per-minute",    Bound.whole(10, 10000)),
            Map.entry("assistant-chat-timeout-seconds",    Bound.whole(30, 900)),
            Map.entry("task-command-timeout-seconds",      Bound.whole(10, 3600)),
            Map.entry("allow-extra-mounts",                Bound.whole(0, 1)),
            Map.entry("todo-hygiene-enabled",              Bound.whole(0, 1)),
            Map.entry("todo-stale-days",                   Bound.whole(7, 365)),
            Map.entry("todo-hygiene-max-per-run",          Bound.whole(10, 2000)),
            Map.entry("run-retention-days",                Bound.whole(0, 730)),
            Map.entry("run-retention-max-per-night",       Bound.whole(10, 5000)),
            Map.entry("live-log-persist-default",          Bound.whole(0, 1)),
            Map.entry("dashboard-layout-retention-count",  Bound.whole(1, 1000)),
            Map.entry("todo-watchdog-enabled",             Bound.whole(0, 1)),
            Map.entry("todo-watchdog-stale-hours",         Bound.whole(1, 720)),
            Map.entry("assistant-max-actions-per-hour",    Bound.whole(1, 500)),
            Map.entry("scheduler-catch-up-on-resume",      Bound.whole(0, 1)),
            Map.entry("scheduler-resume-grace-minutes",    Bound.whole(1, 60)),
            Map.entry("run-auto-resume-enabled",           Bound.whole(0, 1)),
            Map.entry("run-auto-resume-delay-seconds",     Bound.whole(0, 3600)),
            Map.entry("summarization-timeout-seconds",     Bound.whole(30, 600)),
            Map.entry("factory-timeout-seconds",           Bound.whole(60, 3600)),
            Map.entry("wish-total-timeout-minutes",        Bound.whole(5, 1440)),
            Map.entry("wish-workflow-wait-minutes",        Bound.whole(1, 120)),
            Map.entry("wish-default-shell-timeout-seconds",             Bound.whole(30, 3600)),
            Map.entry("wish-default-agentic-total-timeout-seconds",     Bound.whole(60, 86400)),
            Map.entry("model-catalog-ttl-seconds",         Bound.whole(30, 86400)),
            Map.entry("compress-ai-max-input-chars",       Bound.whole(1000, 400000)),
            Map.entry("todo-similarity-keyword-threshold", Bound.fraction(0.05, 0.9)),
            Map.entry("dashboard-report-assist-enabled", Bound.whole(0, 1)),
            // Measurement chain (TODO fc0994d6): the appliers already existed, the bounds were missing - so
            // none of the seven keys was reachable through the public API.
            Map.entry("request-metrics-enabled",                Bound.whole(0, 1)),
            Map.entry("request-metrics-retention-days",         Bound.whole(0, 730)),
            Map.entry("request-metrics-slow-request-ms",        Bound.whole(0, 600000)),
            Map.entry("request-metrics-drift-detection-enabled", Bound.whole(0, 1)),
            Map.entry("request-metrics-drift-factor",           Bound.fraction(1.1, 100.0)),
            Map.entry("request-metrics-drift-min-calls",        Bound.whole(1, 100000)),
            Map.entry("request-metrics-baseline-days",          Bound.whole(1, 365)),
            Map.entry("request-metrics-trend-min-daily-calls",  Bound.whole(1, 100000))
    );

    // Frontend sends camelCase keys, backend uses kebab-case internally
    static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("executionMaxConcurrent",          "execution-max-concurrent"),
            Map.entry("claudeMaxConcurrentCalls",        "claude-max-concurrent-calls"),
            Map.entry("claudeReservedForInteractive",    "claude-reserved-for-interactive"),
            Map.entry("piMaxConcurrentCalls",            "pi-max-concurrent-calls"),
            Map.entry("piReservedForInteractive",        "pi-reserved-for-interactive"),
            Map.entry("codexMaxConcurrentCalls",         "codex-max-concurrent-calls"),
            Map.entry("codexReservedForInteractive",     "codex-reserved-for-interactive"),
            Map.entry("aiCliMaxConcurrentCalls",         "ai-cli-max-concurrent-calls"),
            Map.entry("aiCliReservedForInteractive",     "ai-cli-reserved-for-interactive"),
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
            Map.entry("liveLogPersistDefault",           "live-log-persist-default"),
            Map.entry("dashboardLayoutRetentionCount",   "dashboard-layout-retention-count"),
            Map.entry("todoWatchdogEnabled",             "todo-watchdog-enabled"),
            Map.entry("todoWatchdogStaleHours",          "todo-watchdog-stale-hours"),
            Map.entry("assistantMaxActionsPerHour",      "assistant-max-actions-per-hour"),
            Map.entry("schedulerCatchUpOnResume",        "scheduler-catch-up-on-resume"),
            Map.entry("schedulerResumeGraceMinutes",     "scheduler-resume-grace-minutes"),
            Map.entry("runAutoResumeEnabled",            "run-auto-resume-enabled"),
            Map.entry("runAutoResumeDelaySeconds",       "run-auto-resume-delay-seconds"),
            Map.entry("summarizationTimeoutSeconds",     "summarization-timeout-seconds"),
            Map.entry("factoryTimeoutSeconds",           "factory-timeout-seconds"),
            Map.entry("wishTotalTimeoutMinutes",                    "wish-total-timeout-minutes"),
            Map.entry("wishWorkflowWaitMinutes",                    "wish-workflow-wait-minutes"),
            Map.entry("wishDefaultShellTimeoutSeconds",             "wish-default-shell-timeout-seconds"),
            Map.entry("wishDefaultAgenticTotalTimeoutSeconds",      "wish-default-agentic-total-timeout-seconds"),
            Map.entry("modelCatalogTtlSeconds",          "model-catalog-ttl-seconds"),
            Map.entry("compressAiMaxInputChars",         "compress-ai-max-input-chars"),
            Map.entry("todoSimilarityKeywordThreshold",  "todo-similarity-keyword-threshold"),
            Map.entry("dashboardReportAssistEnabled",    "dashboard-report-assist-enabled"),
            Map.entry("requestMetricsEnabled",                "request-metrics-enabled"),
            Map.entry("requestMetricsRetentionDays",          "request-metrics-retention-days"),
            Map.entry("requestMetricsSlowRequestMs",          "request-metrics-slow-request-ms"),
            Map.entry("requestMetricsDriftDetectionEnabled",  "request-metrics-drift-detection-enabled"),
            Map.entry("requestMetricsDriftFactor",            "request-metrics-drift-factor"),
            Map.entry("requestMetricsDriftMinCalls",          "request-metrics-drift-min-calls"),
            Map.entry("requestMetricsBaselineDays",           "request-metrics-baseline-days"),
            Map.entry("requestMetricsTrendMinDailyCalls",     "request-metrics-trend-min-daily-calls")
    );

    /**
     * Maps a camelCase alias (or an already canonical key) to its canonical kebab-case key.
     *
     * @throws IllegalArgumentException if the key is unknown; the message quotes the key as given
     */
    static String resolve(String key) {
        String resolvedKey = KEY_ALIASES.getOrDefault(key, key);
        if (!BOUNDS.containsKey(resolvedKey)) {
            throw new LocalizedArgumentException(ErrorMessage.UNKNOWN_EXECUTION_SETTING, Map.of("key", String.valueOf(key)));
        }
        return resolvedKey;
    }

    /**
     * Checks a value against the bounds of its key - integer or fraction.
     *
     * @throws IllegalArgumentException if the value is not a number or lies outside the bounds
     */
    static void validate(String key, String value) {
        checkedValue(key, value);
    }

    /**
     * Parses {@code value} and checks it against the bounds of the canonical {@code key}.
     *
     * @throws IllegalArgumentException if the value is not a number or outside the bounds
     */
    static long parseWithinBounds(String key, String value) {
        return (long) checkedValue(key, value);
    }

    private static double checkedValue(String key, String value) {
        Bound bound = BOUNDS.get(key);
        if (bound == null) {
            throw new LocalizedArgumentException(ErrorMessage.UNKNOWN_EXECUTION_SETTING, Map.of("key", String.valueOf(key)));
        }
        double parsed = bound.parse(value);
        if (!bound.contains(parsed)) {
            throw new IllegalArgumentException(key + " must be between " + bound.format(bound.min())
                    + " and " + bound.format(bound.max()) + ", got " + bound.format(parsed));
        }
        return parsed;
    }
}
