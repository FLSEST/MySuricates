package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.RateLimitConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Slf4j
public class ExecutionSettingsService {

    private static final Map<String, long[]> BOUNDS = Map.ofEntries(
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
            Map.entry("scheduler-catch-up-on-resume",      new long[]{0, 1})
    );

    // Frontend sends camelCase keys, backend uses kebab-case internally
    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
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
            Map.entry("schedulerCatchUpOnResume",        "scheduler-catch-up-on-resume")
    );

    private final AppSettingsRepository appSettingsRepository;
    private final DynamicSemaphore executionSemaphore;
    private final HostClaudeService hostClaudeService;
    private final HostClaudeConfig hostClaudeConfig;
    private final ChainConfig chainConfig;
    private final RateLimitConfig rateLimitConfig;
    private final WorkflowExecutor workflowExecutor;
    private final ContainerShellService containerShellService;
    private final SchedulerConfig schedulerConfig;
    private final TodoHygieneConfig todoHygieneConfig;
    private final TaskCommandConfig taskCommandConfig;

    // Defaults from YAML/env (captured at startup)
    private int defaultMaxConcurrent;
    private int defaultMaxConcurrentCalls;
    private int defaultTimeoutSeconds;
    private long defaultInterCallDelayMs;
    private int defaultChainTimeoutMinutes;

    public ExecutionSettingsService(
            AppSettingsRepository appSettingsRepository,
            @Qualifier("executionSemaphore") DynamicSemaphore executionSemaphore,
            @Lazy HostClaudeService hostClaudeService,
            HostClaudeConfig hostClaudeConfig,
            ChainConfig chainConfig,
            RateLimitConfig rateLimitConfig,
            @Lazy WorkflowExecutor workflowExecutor,
            ContainerShellService containerShellService,
            SchedulerConfig schedulerConfig,
            TodoHygieneConfig todoHygieneConfig,
            TaskCommandConfig taskCommandConfig) {
        this.appSettingsRepository = appSettingsRepository;
        this.executionSemaphore = executionSemaphore;
        this.hostClaudeService = hostClaudeService;
        this.hostClaudeConfig = hostClaudeConfig;
        this.chainConfig = chainConfig;
        this.rateLimitConfig = rateLimitConfig;
        this.workflowExecutor = workflowExecutor;
        this.containerShellService = containerShellService;
        this.schedulerConfig = schedulerConfig;
        this.todoHygieneConfig = todoHygieneConfig;
        this.taskCommandConfig = taskCommandConfig;
    }

    @PostConstruct
    public void init() {
        // Capture YAML/env defaults before any DB overrides
        defaultMaxConcurrent = executionSemaphore.getMaxPermits();
        defaultMaxConcurrentCalls = hostClaudeConfig.getMaxConcurrentCalls();
        defaultTimeoutSeconds = hostClaudeConfig.getTimeoutSeconds();
        defaultInterCallDelayMs = hostClaudeConfig.getInterCallDelayMs();
        defaultChainTimeoutMinutes = chainConfig.getWorkflowTimeoutMinutes();

        // Apply DB overrides if present
        applyIfPresent("execution-max-concurrent", v -> executionSemaphore.setMaxPermits(Integer.parseInt(v)));
        applyIfPresent("claude-max-concurrent-calls", v -> hostClaudeService.updateMaxConcurrentCalls(Integer.parseInt(v)));
        applyIfPresent("claude-reserved-for-interactive", v -> {
            hostClaudeConfig.setReservedForInteractive(Integer.parseInt(v));
            hostClaudeService.updateReservedForInteractive(Integer.parseInt(v));
        });
        applyIfPresent("claude-timeout-seconds", v -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(v)));
        applyIfPresent("claude-inter-call-delay-ms", v -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(v)));
        applyIfPresent("chain-workflow-timeout-minutes", v -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(v)));
        applyIfPresent("rate-limit-enabled", v -> rateLimitConfig.setEnabled(Integer.parseInt(v) == 1));
        applyIfPresent("rate-limit-requests-per-minute", v -> rateLimitConfig.setRequestsPerMinute(Integer.parseInt(v)));
        applyIfPresent("assistant-chat-timeout-seconds", v -> hostClaudeConfig.setAssistantChatTimeoutSeconds(Integer.parseInt(v)));
        applyIfPresent("task-command-timeout-seconds", v -> taskCommandConfig.setTimeoutSeconds(Integer.parseInt(v)));
        applyIfPresent("allow-extra-mounts", v -> containerShellService.setAllowExtraMounts(Integer.parseInt(v) == 1));
        applyIfPresent("todo-hygiene-enabled", v -> todoHygieneConfig.setEnabled(Integer.parseInt(v) == 1));
        applyIfPresent("todo-stale-days", v -> todoHygieneConfig.setStaleDays(Integer.parseInt(v)));
        applyIfPresent("todo-hygiene-max-per-run", v -> todoHygieneConfig.setMaxPerRun(Integer.parseInt(v)));
        applyIfPresent("scheduler-catch-up-on-resume", v -> schedulerConfig.setCatchUpOnResume(Integer.parseInt(v) == 1));

        log.info("Execution settings initialized: maxConcurrent={}, maxCalls={}, timeout={}s, delay={}ms, chainTimeout={}min, rateLimit={}/{}rpm, assistantChatTimeout={}s",
                executionSemaphore.getMaxPermits(), hostClaudeConfig.getMaxConcurrentCalls(),
                hostClaudeConfig.getTimeoutSeconds(), hostClaudeConfig.getInterCallDelayMs(),
                chainConfig.getWorkflowTimeoutMinutes(),
                rateLimitConfig.isEnabled() ? "ON" : "OFF", rateLimitConfig.getRequestsPerMinute(),
                hostClaudeConfig.getAssistantChatTimeoutSeconds());
    }

    public void updateAll(Map<String, String> settings) {
        for (var entry : settings.entrySet()) {
            validateAndApply(entry.getKey(), entry.getValue());
        }
    }

    public void validateAndApply(String key, String value) {
        String resolvedKey = KEY_ALIASES.getOrDefault(key, key);
        long[] bounds = BOUNDS.get(resolvedKey);
        if (bounds == null) {
            throw new IllegalArgumentException("Unknown execution setting: " + key);
        }
        key = resolvedKey;

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

        appSettingsRepository.set(key, value);
        applyToRuntime(key, value);
        log.info("Execution setting updated: {} = {}", key, value);
    }

    public Map<String, Object> getCurrentSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionMaxConcurrent", executionSemaphore.getMaxPermits());
        result.put("claudeMaxConcurrentCalls", hostClaudeConfig.getMaxConcurrentCalls());
        result.put("claudeReservedForInteractive", hostClaudeConfig.getReservedForInteractive());
        result.put("claudeTimeoutSeconds", hostClaudeConfig.getTimeoutSeconds());
        result.put("claudeInterCallDelayMs", hostClaudeConfig.getInterCallDelayMs());
        result.put("chainWorkflowTimeoutMinutes", chainConfig.getWorkflowTimeoutMinutes());
        result.put("rateLimitEnabled", rateLimitConfig.isEnabled() ? 1 : 0);
        result.put("rateLimitRequestsPerMinute", rateLimitConfig.getRequestsPerMinute());
        result.put("assistantChatTimeoutSeconds", hostClaudeConfig.getAssistantChatTimeoutSeconds());
        result.put("taskCommandTimeoutSeconds", taskCommandConfig.getTimeoutSeconds());
        result.put("allowExtraMounts", containerShellService.isAllowExtraMounts() ? 1 : 0);
        result.put("todoHygieneEnabled", todoHygieneConfig.isEnabled() ? 1 : 0);
        result.put("todoStaleDays", todoHygieneConfig.getStaleDays());
        result.put("todoHygieneMaxPerRun", todoHygieneConfig.getMaxPerRun());
        result.put("schedulerCatchUpOnResume", schedulerConfig.isCatchUpOnResume() ? 1 : 0);
        result.put("activeRuns", workflowExecutor.getActiveRunCount());
        return result;
    }

    private void applyToRuntime(String key, String value) {
        switch (key) {
            case "execution-max-concurrent" -> executionSemaphore.setMaxPermits(Integer.parseInt(value));
            case "claude-max-concurrent-calls" -> hostClaudeService.updateMaxConcurrentCalls(Integer.parseInt(value));
            case "claude-reserved-for-interactive" -> {
                hostClaudeConfig.setReservedForInteractive(Integer.parseInt(value));
                hostClaudeService.updateReservedForInteractive(Integer.parseInt(value));
            }
            case "claude-timeout-seconds" -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(value));
            case "claude-inter-call-delay-ms" -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(value));
            case "chain-workflow-timeout-minutes" -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(value));
            case "rate-limit-enabled" -> rateLimitConfig.setEnabled(Integer.parseInt(value) == 1);
            case "rate-limit-requests-per-minute" -> rateLimitConfig.setRequestsPerMinute(Integer.parseInt(value));
            case "assistant-chat-timeout-seconds" -> hostClaudeConfig.setAssistantChatTimeoutSeconds(Integer.parseInt(value));
            case "task-command-timeout-seconds" -> taskCommandConfig.setTimeoutSeconds(Integer.parseInt(value));
            case "allow-extra-mounts" -> containerShellService.setAllowExtraMounts(Integer.parseInt(value) == 1);
            case "todo-hygiene-enabled" -> todoHygieneConfig.setEnabled(Integer.parseInt(value) == 1);
            case "todo-stale-days" -> todoHygieneConfig.setStaleDays(Integer.parseInt(value));
            case "todo-hygiene-max-per-run" -> todoHygieneConfig.setMaxPerRun(Integer.parseInt(value));
            case "scheduler-catch-up-on-resume" -> schedulerConfig.setCatchUpOnResume(Integer.parseInt(value) == 1);
        }
    }

    private void applyIfPresent(String key, java.util.function.Consumer<String> applier) {
        appSettingsRepository.get(key).filter(v -> !v.isBlank()).ifPresent(v -> {
            try {
                long[] bounds = BOUNDS.get(key);
                long parsed = Long.parseLong(v);
                if (parsed >= bounds[0] && parsed <= bounds[1]) {
                    applier.accept(v);
                    log.debug("Applied DB override for {}: {}", key, v);
                } else {
                    log.warn("Ignoring out-of-bounds DB value for {}: {} (bounds: {}-{})", key, v, bounds[0], bounds[1]);
                }
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric DB value for {}: {}", key, v);
            }
        });
    }
}
