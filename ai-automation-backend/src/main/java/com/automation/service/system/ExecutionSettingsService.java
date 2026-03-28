package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
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

    private static final Map<String, long[]> BOUNDS = Map.of(
            "execution-max-concurrent",       new long[]{1, 50},
            "claude-max-concurrent-calls",    new long[]{1, 20},
            "claude-timeout-seconds",         new long[]{30, 3600},
            "claude-inter-call-delay-ms",     new long[]{0, 30000},
            "chain-workflow-timeout-minutes",  new long[]{5, 1440}
    );

    // Frontend sends camelCase keys, backend uses kebab-case internally
    private static final Map<String, String> KEY_ALIASES = Map.of(
            "executionMaxConcurrent",       "execution-max-concurrent",
            "claudeMaxConcurrentCalls",     "claude-max-concurrent-calls",
            "claudeTimeoutSeconds",         "claude-timeout-seconds",
            "claudeInterCallDelayMs",       "claude-inter-call-delay-ms",
            "chainWorkflowTimeoutMinutes",  "chain-workflow-timeout-minutes"
    );

    private final AppSettingsRepository appSettingsRepository;
    private final DynamicSemaphore executionSemaphore;
    private final HostClaudeService hostClaudeService;
    private final HostClaudeConfig hostClaudeConfig;
    private final ChainConfig chainConfig;
    private final WorkflowExecutor workflowExecutor;

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
            @Lazy WorkflowExecutor workflowExecutor) {
        this.appSettingsRepository = appSettingsRepository;
        this.executionSemaphore = executionSemaphore;
        this.hostClaudeService = hostClaudeService;
        this.hostClaudeConfig = hostClaudeConfig;
        this.chainConfig = chainConfig;
        this.workflowExecutor = workflowExecutor;
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
        applyIfPresent("claude-timeout-seconds", v -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(v)));
        applyIfPresent("claude-inter-call-delay-ms", v -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(v)));
        applyIfPresent("chain-workflow-timeout-minutes", v -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(v)));

        log.info("Execution settings initialized: maxConcurrent={}, maxCalls={}, timeout={}s, delay={}ms, chainTimeout={}min",
                executionSemaphore.getMaxPermits(), hostClaudeConfig.getMaxConcurrentCalls(),
                hostClaudeConfig.getTimeoutSeconds(), hostClaudeConfig.getInterCallDelayMs(),
                chainConfig.getWorkflowTimeoutMinutes());
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
        result.put("claudeTimeoutSeconds", hostClaudeConfig.getTimeoutSeconds());
        result.put("claudeInterCallDelayMs", hostClaudeConfig.getInterCallDelayMs());
        result.put("chainWorkflowTimeoutMinutes", chainConfig.getWorkflowTimeoutMinutes());
        result.put("activeRuns", workflowExecutor.getActiveRunCount());
        return result;
    }

    private void applyToRuntime(String key, String value) {
        switch (key) {
            case "execution-max-concurrent" -> executionSemaphore.setMaxPermits(Integer.parseInt(value));
            case "claude-max-concurrent-calls" -> hostClaudeService.updateMaxConcurrentCalls(Integer.parseInt(value));
            case "claude-timeout-seconds" -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(value));
            case "claude-inter-call-delay-ms" -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(value));
            case "chain-workflow-timeout-minutes" -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(value));
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
