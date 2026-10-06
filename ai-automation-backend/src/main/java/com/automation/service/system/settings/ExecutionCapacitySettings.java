package com.automation.service.system.settings;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.concurrency.CliCallThrottle;
import com.automation.service.ai.AiCliThrottles;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Concurrency and time budgets of the execution: run slots, AI CLI limits, timeouts.
 *
 * <p>The AI CLI limits are read back from the throttles themselves, not from the startup configs – a
 * runtime change shows up at once (TODO bd5df8d7: the page showed the startup value of Claude's limit).
 */
@Component
@Order(1)
public class ExecutionCapacitySettings implements RuntimeSettingsGroup {

    private final DynamicSemaphore executionSemaphore;
    private final AiCliThrottles throttles;
    private final HostClaudeConfig hostClaudeConfig;
    private final ChainConfig chainConfig;
    private final TaskCommandConfig taskCommandConfig;
    private final WorkflowExecutionRegistry executionRegistry;

    public ExecutionCapacitySettings(@Qualifier("executionSemaphore") DynamicSemaphore executionSemaphore,
                                     AiCliThrottles throttles, HostClaudeConfig hostClaudeConfig,
                                     ChainConfig chainConfig, TaskCommandConfig taskCommandConfig,
                                     WorkflowExecutionRegistry executionRegistry) {
        this.executionSemaphore = executionSemaphore;
        this.throttles = throttles;
        this.hostClaudeConfig = hostClaudeConfig;
        this.chainConfig = chainConfig;
        this.taskCommandConfig = taskCommandConfig;
        this.executionRegistry = executionRegistry;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> m = new LinkedHashMap<>();
        m.put("execution-max-concurrent", v -> executionSemaphore.setMaxPermits(Integer.parseInt(v)));
        limits(m, "claude", throttles.provider(AiCliThrottles.CLAUDE));
        limits(m, "pi", throttles.provider(AiCliThrottles.PI));
        limits(m, "codex", throttles.provider(AiCliThrottles.CODEX));
        limits(m, "ai-cli", throttles.total());
        m.put("claude-timeout-seconds", v -> hostClaudeConfig.setTimeoutSeconds(Integer.parseInt(v)));
        m.put("claude-inter-call-delay-ms", v -> hostClaudeConfig.setInterCallDelayMs(Long.parseLong(v)));
        m.put("chain-workflow-timeout-minutes", v -> chainConfig.setWorkflowTimeoutMinutes(Integer.parseInt(v)));
        m.put("assistant-chat-timeout-seconds", v -> hostClaudeConfig.setAssistantChatTimeoutSeconds(Integer.parseInt(v)));
        m.put("task-command-timeout-seconds", v -> taskCommandConfig.setTimeoutSeconds(Integer.parseInt(v)));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        out.put("executionMaxConcurrent", executionSemaphore.getMaxPermits());
        values(out, "claude", throttles.provider(AiCliThrottles.CLAUDE));
        values(out, "pi", throttles.provider(AiCliThrottles.PI));
        values(out, "codex", throttles.provider(AiCliThrottles.CODEX));
        values(out, "aiCli", throttles.total());
        out.put("claudeTimeoutSeconds", hostClaudeConfig.getTimeoutSeconds());
        out.put("claudeInterCallDelayMs", hostClaudeConfig.getInterCallDelayMs());
        out.put("chainWorkflowTimeoutMinutes", chainConfig.getWorkflowTimeoutMinutes());
        out.put("assistantChatTimeoutSeconds", hostClaudeConfig.getAssistantChatTimeoutSeconds());
        out.put("taskCommandTimeoutSeconds", taskCommandConfig.getTimeoutSeconds());
        out.put("activeRuns", executionRegistry.getActiveRunCount());   // running + queued
        out.put("queuedRuns", executionRegistry.getQueuedRunCount());
    }

    /** {@code <prefix>-max-concurrent-calls} and {@code <prefix>-reserved-for-interactive} of one throttle. */
    private static void limits(Map<String, Consumer<String>> m, String prefix, CliCallThrottle throttle) {
        m.put(prefix + "-max-concurrent-calls", v -> throttle.setMaxConcurrent(Integer.parseInt(v)));
        m.put(prefix + "-reserved-for-interactive", v -> throttle.setReservedForInteractive(Integer.parseInt(v)));
    }

    private static void values(Map<String, Object> out, String prefix, CliCallThrottle throttle) {
        out.put(prefix + "MaxConcurrentCalls", throttle.maxConcurrent());
        out.put(prefix + "ReservedForInteractive", throttle.reservedForInteractive());
    }
}
