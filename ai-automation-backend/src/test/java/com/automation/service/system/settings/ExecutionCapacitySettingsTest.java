package com.automation.service.system.settings;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.AiCliConfig;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.HostPiConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.service.ai.AiCliThrottles;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.automation.service.system.settings.SettingsGroupsTestSupport.apply;
import static com.automation.service.system.settings.SettingsGroupsTestSupport.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionCapacitySettingsTest {

    private final DynamicSemaphore semaphore = new DynamicSemaphore(3);
    private final HostClaudeConfig claudeConfig = new HostClaudeConfig();
    private final AiCliThrottles throttles = new AiCliThrottles(claudeConfig, new HostPiConfig(), new AiCliConfig());
    private final ChainConfig chain = new ChainConfig();
    private final TaskCommandConfig task = new TaskCommandConfig();
    private final WorkflowExecutionRegistry registry = mock(WorkflowExecutionRegistry.class);
    private final ExecutionCapacitySettings settings =
            new ExecutionCapacitySettings(semaphore, throttles, claudeConfig, chain, task, registry);

    @Test
    void maxConcurrentSetztDieSemaphorGroesse() {
        apply(settings, "execution-max-concurrent", "6");

        assertThat(semaphore.getMaxPermits()).isEqualTo(6);
    }

    @ParameterizedTest
    @CsvSource({"claude, CLAUDE", "pi, PI", "codex, CODEX"})
    void providerLimitsChangeTheThrottleOfThatProvider(String prefix, String provider) {
        apply(settings, prefix + "-max-concurrent-calls", "8");
        apply(settings, prefix + "-reserved-for-interactive", "3");

        assertThat(throttles.provider(provider).maxConcurrent()).isEqualTo(8);
        assertThat(throttles.provider(provider).backgroundLimit()).isEqualTo(5);
    }

    @Test
    void overallLimitChangesTheThrottleAcrossProviders() {
        apply(settings, "ai-cli-max-concurrent-calls", "12");
        apply(settings, "ai-cli-reserved-for-interactive", "2");

        assertThat(throttles.total().maxConcurrent()).isEqualTo(12);
        assertThat(throttles.total().backgroundLimit()).isEqualTo(10);
    }

    // bd5df8d7: the page read Claude's limit from the startup config and showed it again after a change
    @Test
    void currentValuesShowTheLimitsInForceNotTheStartupValues() {
        apply(settings, "claude-max-concurrent-calls", "9");
        apply(settings, "codex-reserved-for-interactive", "2");
        apply(settings, "ai-cli-max-concurrent-calls", "14");

        var v = values(settings);

        assertThat(claudeConfig.getMaxConcurrentCalls()).isEqualTo(5);
        assertThat(v).containsEntry("claudeMaxConcurrentCalls", 9)
                .containsEntry("codexReservedForInteractive", 2)
                .containsEntry("aiCliMaxConcurrentCalls", 14)
                .containsEntry("piMaxConcurrentCalls", 5)
                .containsEntry("aiCliReservedForInteractive", 1);
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
                        "piMaxConcurrentCalls", "piReservedForInteractive",
                        "codexMaxConcurrentCalls", "codexReservedForInteractive",
                        "aiCliMaxConcurrentCalls", "aiCliReservedForInteractive",
                        "claudeInterCallDelayMs", "assistantChatTimeoutSeconds");
    }

    @Test
    void alleAppliersSindRegistriert() {
        assertThat(settings.appliers()).containsOnlyKeys("execution-max-concurrent",
                "claude-max-concurrent-calls", "claude-reserved-for-interactive",
                "pi-max-concurrent-calls", "pi-reserved-for-interactive",
                "codex-max-concurrent-calls", "codex-reserved-for-interactive",
                "ai-cli-max-concurrent-calls", "ai-cli-reserved-for-interactive", "claude-timeout-seconds",
                "claude-inter-call-delay-ms", "chain-workflow-timeout-minutes",
                "assistant-chat-timeout-seconds", "task-command-timeout-seconds");
    }
}
