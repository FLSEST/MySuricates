package com.automation.service.system.settings;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Parallelitaet und Zeitbudgets der Ausfuehrung: Run-Slots, Claude-CLI-Drossel, Timeouts. */
@Component
@Order(1)
public class ExecutionCapacitySettings implements RuntimeSettingsGroup {

    private final DynamicSemaphore executionSemaphore;
    private final HostClaudeService hostClaudeService;
    private final HostClaudeConfig hostClaudeConfig;
    private final ChainConfig chainConfig;
    private final TaskCommandConfig taskCommandConfig;
    private final WorkflowExecutionRegistry executionRegistry;

    public ExecutionCapacitySettings(@Qualifier("executionSemaphore") DynamicSemaphore executionSemaphore,
                                     HostClaudeService hostClaudeService, HostClaudeConfig hostClaudeConfig,
                                     ChainConfig chainConfig, TaskCommandConfig taskCommandConfig,
                                     WorkflowExecutionRegistry executionRegistry) {
        this.executionSemaphore = executionSemaphore;
        this.hostClaudeService = hostClaudeService;
        this.hostClaudeConfig = hostClaudeConfig;
        this.chainConfig = chainConfig;
        this.taskCommandConfig = taskCommandConfig;
        this.executionRegistry = executionRegistry;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
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
        m.put("assistant-chat-timeout-seconds", v -> hostClaudeConfig.setAssistantChatTimeoutSeconds(Integer.parseInt(v)));
        m.put("task-command-timeout-seconds", v -> taskCommandConfig.setTimeoutSeconds(Integer.parseInt(v)));
        return m;
    }

    @Override
    public void currentValues(Map<String, Object> out) {
        out.put("executionMaxConcurrent", executionSemaphore.getMaxPermits());
        out.put("claudeMaxConcurrentCalls", hostClaudeConfig.getMaxConcurrentCalls());
        out.put("claudeReservedForInteractive", hostClaudeConfig.getReservedForInteractive());
        out.put("claudeTimeoutSeconds", hostClaudeConfig.getTimeoutSeconds());
        out.put("claudeInterCallDelayMs", hostClaudeConfig.getInterCallDelayMs());
        out.put("chainWorkflowTimeoutMinutes", chainConfig.getWorkflowTimeoutMinutes());
        out.put("assistantChatTimeoutSeconds", hostClaudeConfig.getAssistantChatTimeoutSeconds());
        out.put("taskCommandTimeoutSeconds", taskCommandConfig.getTimeoutSeconds());
        out.put("activeRuns", executionRegistry.getActiveRunCount());   // running + queued
        out.put("queuedRuns", executionRegistry.getQueuedRunCount());
    }
}
