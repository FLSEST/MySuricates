package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.AssistantGuardrailsConfig;
import com.automation.config.ChainConfig;
import com.automation.config.CompressionConfig;
import com.automation.config.DashboardLayoutConfig;
import com.automation.config.DashboardReportAssistConfig;
import com.automation.config.FactoryConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.RateLimitConfig;
import com.automation.config.RequestMetricsConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.SummarizationConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.config.RunRetentionConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.config.TodoSimilarityConfig;
import com.automation.config.WishConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.catalog.ModelCatalogService;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.workflow.RunDeletionPolicy;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
    private final DashboardLayoutConfig dashboardLayoutConfig;
    private final ModelCatalogService modelCatalogService;
    private final CompressionConfig compressionConfig;
    private final TodoSimilarityConfig todoSimilarityConfig;
    private final RequestMetricsConfig requestMetricsConfig;
    private final DashboardReportAssistConfig dashboardReportAssistConfig;
    private final RunDeletionPolicy runDeletionPolicy;

    private static final String RUN_RETENTION_DAYS = "run-retention-days";

    /** Canonical key → runtime setter, in the order the DB overrides are applied at startup. */
    private final Map<String, Consumer<String>> appliers;

    /**
     * Nur für den Wächter-Test (TODO fc0994d6): Ein Applier ohne Eintrag in
     * {@link ExecutionSettingKeys#BOUNDS} ist über die öffentliche API unerreichbar, weil
     * {@code validateAndApply} zuerst {@code resolve(key)} ruft — die sieben
     * {@code request-metrics-*}-Schlüssel waren so monatelang tote Registrierungen.
     */
    Set<String> applierKeys() {
        return appliers.keySet();
    }

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
            RunRetentionConfig runRetentionConfig,
            DashboardLayoutConfig dashboardLayoutConfig,
            ModelCatalogService modelCatalogService,
            CompressionConfig compressionConfig,
            TodoSimilarityConfig todoSimilarityConfig,
            RequestMetricsConfig requestMetricsConfig,
            DashboardReportAssistConfig dashboardReportAssistConfig,
            RunDeletionPolicy runDeletionPolicy) {
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
        this.dashboardLayoutConfig = dashboardLayoutConfig;
        this.modelCatalogService = modelCatalogService;
        this.compressionConfig = compressionConfig;
        this.todoSimilarityConfig = todoSimilarityConfig;
        this.requestMetricsConfig = requestMetricsConfig;
        this.dashboardReportAssistConfig = dashboardReportAssistConfig;
        this.runDeletionPolicy = runDeletionPolicy;
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
        m.put(RUN_RETENTION_DAYS, v -> runRetentionConfig.setRetentionDays(Integer.parseInt(v)));
        m.put("run-retention-max-per-night", v -> runRetentionConfig.setMaxPerNight(Integer.parseInt(v)));
        m.put("dashboard-layout-retention-count", v -> dashboardLayoutConfig.setRetentionCount(Integer.parseInt(v)));
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
        m.put("model-catalog-ttl-seconds", v -> modelCatalogService.setTtlSeconds(Integer.parseInt(v)));
        m.put("compress-ai-max-input-chars", v -> compressionConfig.setMaxAiInputChars(Integer.parseInt(v)));
        m.put("todo-similarity-keyword-threshold", v -> todoSimilarityConfig.setKeywordThreshold(Double.parseDouble(v)));
        m.put("request-metrics-enabled", v -> requestMetricsConfig.setEnabled(Integer.parseInt(v) == 1));
        m.put("request-metrics-retention-days", v -> requestMetricsConfig.setRetentionDays(Integer.parseInt(v)));
        m.put("request-metrics-slow-request-ms", v -> requestMetricsConfig.setSlowRequestMs(Long.parseLong(v)));
        m.put("request-metrics-drift-detection-enabled", v -> requestMetricsConfig.setDriftDetectionEnabled(Integer.parseInt(v) == 1));
        m.put("request-metrics-drift-factor", v -> requestMetricsConfig.setDriftFactor(Double.parseDouble(v)));
        m.put("request-metrics-drift-min-calls", v -> requestMetricsConfig.setDriftMinCalls(Integer.parseInt(v)));
        m.put("request-metrics-baseline-days", v -> requestMetricsConfig.setBaselineDays(Integer.parseInt(v)));
        m.put("dashboard-report-assist-enabled", v -> dashboardReportAssistConfig.setEnabled(Integer.parseInt(v) == 1));
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
        // Refuse before anything is applied — a batch must not land half-way (TODO 8dbd7e10)
        settings.forEach(this::guardRetention);
        for (var entry : settings.entrySet()) {
            validateAndApply(entry.getKey(), entry.getValue());
        }
    }

    public void validateAndApply(String key, String value) {
        key = ExecutionSettingKeys.resolve(key);
        ExecutionSettingKeys.validate(key, value);
        guardRetention(key, value);

        appSettingsRepository.set(key, value);
        appliers.get(key).accept(value);
        log.info("Execution setting updated: {} = {}", key, value);
    }

    private void guardRetention(String key, String value) {
        if (RUN_RETENTION_DAYS.equals(ExecutionSettingKeys.resolve(key))) {
            ExecutionSettingKeys.validate(RUN_RETENTION_DAYS, value);
            runDeletionPolicy.requireRetentionChangeAllowed(runRetentionConfig.getRetentionDays(), Integer.parseInt(value));
        }
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
        result.put("dashboardLayoutRetentionCount", dashboardLayoutConfig.getRetentionCount());
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
        result.put("modelCatalogTtlSeconds", modelCatalogService.getTtlSeconds());
        result.put("compressAiMaxInputChars", compressionConfig.getMaxAiInputChars());
        result.put("todoSimilarityKeywordThreshold", todoSimilarityConfig.getKeywordThreshold());
        result.put("dashboardReportAssistEnabled", dashboardReportAssistConfig.isEnabled() ? 1 : 0);
        result.put("activeRuns", executionRegistry.getActiveRunCount());   // running + queued
        result.put("queuedRuns", executionRegistry.getQueuedRunCount());
        return result;
    }

    /**
     * Ein einzelner unbrauchbarer DB-Wert darf den Start nicht kippen: {@link #init} laeuft in
     * {@code @PostConstruct}, eine Exception hier waere ein Startabbruch. Deshalb umschliesst der
     * Fang auch das Anwenden selbst, nicht nur die Pruefung.
     */
    private void applyIfPresent(String key, Consumer<String> applier) {
        appSettingsRepository.get(key).filter(v -> !v.isBlank()).ifPresent(v -> {
            try {
                ExecutionSettingKeys.validate(key, v);
                applier.accept(v);
                log.debug("Applied DB override for {}: {}", key, v);
            } catch (RuntimeException e) {
                log.warn("Ignoring unusable DB value for {}: {} ({})", key, v, e.toString());
            }
        });
    }
}
