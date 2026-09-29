package com.automation.service.system.settings;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.Test;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionCapacitySettingsTest {

    private final DynamicSemaphore semaphore = new DynamicSemaphore(3);
    private final HostClaudeService claude = mock(HostClaudeService.class);
    private final HostClaudeConfig claudeConfig = new HostClaudeConfig();
    private final ChainConfig chain = new ChainConfig();
    private final TaskCommandConfig task = new TaskCommandConfig();
    private final WorkflowExecutionRegistry registry = mock(WorkflowExecutionRegistry.class);
    private final ExecutionCapacitySettings settings =
            new ExecutionCapacitySettings(semaphore, claude, claudeConfig, chain, task, registry);

    @Test
    void maxConcurrentSetztDieSemaphorGroesse() {
        apply(settings, "execution-max-concurrent", "6");

        assertThat(semaphore.getMaxPermits()).isEqualTo(6);
    }

    @Test
    void claudeAufrufeLeitenAnDenServiceWeiter() {
        apply(settings, "claude-max-concurrent-calls", "8");

        verify(claude).updateMaxConcurrentCalls(8);
    }

    @Test
    void reservierungWirdInConfigUndServiceGeschrieben() {
        apply(settings, "claude-reserved-for-interactive", "2");

        assertThat(claudeConfig.getReservedForInteractive()).isEqualTo(2);
        verify(claude).updateReservedForInteractive(2);
    }

    @Test
    void zeitbudgetsLandenInDenConfigs() {
        apply(settings, "claude-timeout-seconds", "111");
        apply(settings, "claude-inter-call-delay-ms", "5000000000");
        apply(settings, "chain-workflow-timeout-minutes", "15");
        apply(settings, "assistant-chat-timeout-seconds", "77");
        apply(settings, "task-command-timeout-seconds", "33");

        assertThat(claudeConfig.getTimeoutSeconds()).isEqualTo(111);
        assertThat(claudeConfig.getInterCallDelayMs()).isEqualTo(5_000_000_000L);
        assertThat(chain.getWorkflowTimeoutMinutes()).isEqualTo(15);
        assertThat(claudeConfig.getAssistantChatTimeoutSeconds()).isEqualTo(77);
        assertThat(task.getTimeoutSeconds()).isEqualTo(33);
    }

    @Test
    void nichtNumerischerWertWirftNumberFormatException() {
        assertThatThrownBy(() -> apply(settings, "execution-max-concurrent", "viele"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void currentValuesLesenConfigsUndRegistry() {
        claudeConfig.setTimeoutSeconds(99);
        chain.setWorkflowTimeoutMinutes(20);
        when(registry.getActiveRunCount()).thenReturn(4);
        when(registry.getQueuedRunCount()).thenReturn(1);

        var v = values(settings);

        assertThat(v).containsEntry("executionMaxConcurrent", 3)
                .containsEntry("claudeTimeoutSeconds", 99)
                .containsEntry("chainWorkflowTimeoutMinutes", 20)
                .containsEntry("activeRuns", 4)
                .containsEntry("queuedRuns", 1)
                .containsEntry("taskCommandTimeoutSeconds", task.getTimeoutSeconds())
                .containsKeys("claudeMaxConcurrentCalls", "claudeReservedForInteractive",
                        "claudeInterCallDelayMs", "assistantChatTimeoutSeconds");
    }

    @Test
    void alleAppliersSindRegistriert() {
        assertThat(settings.appliers()).containsOnlyKeys("execution-max-concurrent",
                "claude-max-concurrent-calls", "claude-reserved-for-interactive", "claude-timeout-seconds",
                "claude-inter-call-delay-ms", "chain-workflow-timeout-minutes",
                "assistant-chat-timeout-seconds", "task-command-timeout-seconds");
    }
}
