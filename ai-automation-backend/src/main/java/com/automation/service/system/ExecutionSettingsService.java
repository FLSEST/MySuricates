package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.AssistantGuardrailsConfig;
import com.automation.config.ChainConfig;
import com.automation.config.FactoryConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.RateLimitConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.SummarizationConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.config.RunRetentionConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.config.WishConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Applies runtime-tunable execution settings: DB overrides at startup, validated updates from
 * the settings UI, and the current-values snapshot. Which keys exist and what values are legal
 * lives in {@link ExecutionSettingKeys}; this class only owns the key → runtime-setter table.
 */
@Service
@Slf4j
public class ExecutionSettingsService {

    private final AppSettingsRepository appSettingsRepository;
    private final DynamicSemaphore executionSemaphore;
    private final HostClaudeService hostClaudeService;
    private final HostClaudeConfig hostClaudeConfig;
    private final ChainConfig chainConfig;
    private final RateLimitConfig rateLimitConfig;
    private final WorkflowExecutionRegistry executionRegistry;
    private final ContainerShellService containerShellService;
    private final SchedulerConfig schedulerConfig;
    private final TodoHygieneConfig todoHygieneConfig;
    private final TodoGhostWatchdogConfig todoGhostWatchdogConfig;
    private final AssistantGuardrailsConfig assistantGuardrailsConfig;
    private final TaskCommandConfig taskCommandConfig;
    private final SummarizationConfig summarizationConfig;
    private final FactoryConfig factoryConfig;
    private final WishConfig wishConfig;
    private final RunRetentionConfig runRetentionConfig;

    /** Canonical key → runtime setter, in the order the DB overrides are applied at startup. */
    private final Map<String, Consumer<String>> appliers;

    public ExecutionSettingsService(
            AppSettingsRepository appSettingsRepository,
            @Qualifier("executionSemaphore") DynamicSemaphore executionSemaphore,
            HostClaudeService hostClaudeService,
            HostClaudeConfig hostClaudeConfig,
            ChainConfig chainConfig,
            RateLimitConfig rateLimitConfig,
            WorkflowExecutionRegistry executionRegistry,
            ContainerShellService containerShellService,
            SchedulerConfig schedulerConfig,
            TodoHygieneConfig todoHygieneConfig,
            TodoGhostWatchdogConfig todoGhostWatchdogConfig,
            AssistantGuardrailsConfig assistantGuardrailsConfig,
            TaskCommandConfig taskCommandConfig,
            SummarizationConfig summarizationConfig,
            FactoryConfig factoryConfig,
            WishConfig wishConfig,
            RunRetentionConfig runRetentionConfig) {
        this.appSettingsRepository = appSettingsRepository;
        this.executionSemaphore = executionSemaphore;
        this.hostClaudeService = hostClaudeService;
        this.hostClaudeConfig = hostClaudeConfig;
        this.chainConfig = chainConfig;
        this.rateLimitConfig = rateLimitConfig;
        this.executionRegistry = executionRegistry;
        this.containerShellService = containerShellService;
        this.schedulerConfig = schedulerConfig;
        this.todoHygieneConfig = todoHygieneConfig;
        this.todoGhostWatchdogConfig = todoGhostWatchdogConfig;
        this.assistantGuardrailsConfig = assistantGuardrailsConfig;
        this.taskCommandConfig = taskCommandConfig;
        this.summarizationConfig = summarizationConfig;
        this.factoryConfig = factoryConfig;
        this.wishConfig = wishConfig;
        this.runRetentionConfig = runRetentionConfig;
        this.appliers = buildAppliers();
    }

    private Map<String, Consumer<String>> buildAppliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("execution-max-concurrent", v -> executionSemaphore.setMaxPermits(Integer.parseInt(v)));
        m.put("claude-max-concurrent-calls", v -> hostClaudeService.updateMaxConcurrentCalls(Integer.parseInt(v)));
        m.put("claude-reserved-for-interactive", v -> {
            hostClaudeConfig.setReservedForInteractive(Integer.parseInt(v));
            hostClaudeService.updateReservedForInteractive(Integer.parseInt(v));
        });
        m.put("claude-timeout-seconds", v -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("claude-inter-call-delay-ms", v -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(v)));
        m.put("chain-workflow-timeout-minutes", v -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(v)));
        m.put("rate-limit-enabled", v -> rateLimitConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("rate-limit-requests-per-minute", v -> rateLimitConfig.setRequestsPerMinute(Integer.parseInt(v)));
        m.put("assistant-chat-timeout-seconds", v -> hostClaudeConfig.setAssistantChatTimeoutSeconds(Integer.parseInt(v)));
        m.put("task-command-timeout-seconds", v -> taskCommandConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("allow-extra-mounts", v -> containerShellService.setAllowExtraMounts(Integer.parseInt(v) == 1));
        m.put("todo-hygiene-enabled", v -> todoHygieneConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("todo-stale-days", v -> todoHygieneConfig.setStaleDays(Integer.parseInt(v)));
        m.put("todo-hygiene-max-per-run", v -> todoHygieneConfig.setMaxPerRun(Integer.parseInt(v)));
        m.put("run-retention-days", v -> runRetentionConfig.setRetentionDays(Integer.parseInt(v)));
        m.put("run-retention-max-per-night", v -> runRetentionConfig.setMaxPerNight(Integer.parseInt(v)));
        m.put("todo-watchdog-enabled", v -> todoGhostWatchdogConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("todo-watchdog-stale-hours", v -> todoGhostWatchdogConfig.setStaleHours(Integer.parseInt(v)));
        m.put("assistant-max-actions-per-hour", v -> assistantGuardrailsConfig.setMaxActionsPerHour(Integer.parseInt(v)));
        m.put("scheduler-catch-up-on-resume", v -> schedulerConfig.setCatchUpOnResume(Integer.parseInt(v) == 1));
        m.put("scheduler-resume-grace-minutes", v -> schedulerConfig.setResumeGraceMinutes(Long.parseLong(v)));
        m.put("summarization-timeout-seconds", v -> summarizationConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("factory-timeout-seconds", v -> factoryConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("wish-total-timeout-minutes", v -> wishConfig.setTotalTimeoutMinutes(Integer.parseInt(v)));
        m.put("wish-workflow-wait-minutes", v -> wishConfig.setWorkflowWaitMinutes(Integer.parseInt(v)));
        m.put("wish-default-shell-timeout-seconds", v -> wishConfig.setDefaultShellTimeoutSeconds(Integer.parseInt(v)));
        m.put("wish-default-agentic-total-timeout-seconds", v -> wishConfig.setDefaultAgenticTotalTimeoutSeconds(Integer.parseInt(v)));
        return m;
    }

    @PostConstruct
    public void init() {
        // Apply DB overrides if present
        appliers.forEach(this::applyIfPresent);

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
        key = ExecutionSettingKeys.resolve(key);
        ExecutionSettingKeys.parseWithinBounds(key, value);

        appSettingsRepository.set(key, value);
        appliers.get(key).accept(value);
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
        result.put("runRetentionDays", runRetentionConfig.getRetentionDays());
        result.put("runRetentionMaxPerNight", runRetentionConfig.getMaxPerNight());
        result.put("todoWatchdogEnabled", todoGhostWatchdogConfig.isEnabled() ? 1 : 0);
        result.put("todoWatchdogStaleHours", todoGhostWatchdogConfig.getStaleHours());
        result.put("assistantMaxActionsPerHour", assistantGuardrailsConfig.getMaxActionsPerHour());
        result.put("schedulerCatchUpOnResume", schedulerConfig.isCatchUpOnResume() ? 1 : 0);
        result.put("schedulerResumeGraceMinutes", schedulerConfig.getResumeGraceMinutes());
        result.put("summarizationTimeoutSeconds", summarizationConfig.getTimeoutSeconds());
        result.put("factoryTimeoutSeconds", factoryConfig.getTimeoutSeconds());
        result.put("wishTotalTimeoutMinutes", wishConfig.getTotalTimeoutMinutes());
        result.put("wishWorkflowWaitMinutes", wishConfig.getWorkflowWaitMinutes());
        result.put("wishDefaultShellTimeoutSeconds", wishConfig.getDefaultShellTimeoutSeconds());
        result.put("wishDefaultAgenticTotalTimeoutSeconds", wishConfig.getDefaultAgenticTotalTimeoutSeconds());
        result.put("activeRuns", executionRegistry.getActiveRunCount());
        return result;
    }

    private void applyIfPresent(String key, Consumer<String> applier) {
        appSettingsRepository.get(key).filter(v -> !v.isBlank()).ifPresent(v -> {
            try {
                long[] bounds = ExecutionSettingKeys.bounds(key);
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
