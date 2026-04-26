package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.RateLimitConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionSettingsServiceTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private HostClaudeService hostClaudeService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ContainerShellService containerShellService;

    private DynamicSemaphore executionSemaphore;
    private HostClaudeConfig hostClaudeConfig;
    private ChainConfig chainConfig;
    private RateLimitConfig rateLimitConfig;
    private ExecutionSettingsService service;

    @BeforeEach
    void setUp() {
        executionSemaphore = new DynamicSemaphore(10);
        hostClaudeConfig = new HostClaudeConfig();
        chainConfig = new ChainConfig();
        rateLimitConfig = new RateLimitConfig();

        service = new ExecutionSettingsService(
                appSettingsRepository, executionSemaphore,
                hostClaudeService, hostClaudeConfig, chainConfig,
                rateLimitConfig, workflowExecutor, containerShellService
        );
    }

    @Test
    void init_withNoDbValues_keepsDefaults() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());

        service.init();

        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(10);
        assertThat(hostClaudeConfig.getTimeoutSeconds()).isEqualTo(300);
        assertThat(hostClaudeConfig.getInterCallDelayMs()).isEqualTo(0);
        assertThat(chainConfig.getWorkflowTimeoutMinutes()).isEqualTo(180);
    }

    @Test
    void init_withDbOverrides_appliesThem() {
        when(appSettingsRepository.get("execution-max-concurrent")).thenReturn(Optional.of("5"));
        when(appSettingsRepository.get("claude-max-concurrent-calls")).thenReturn(Optional.of("2"));
        when(appSettingsRepository.get("claude-reserved-for-interactive")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-timeout-seconds")).thenReturn(Optional.of("600"));
        when(appSettingsRepository.get("claude-inter-call-delay-ms")).thenReturn(Optional.of("100"));
        when(appSettingsRepository.get("chain-workflow-timeout-minutes")).thenReturn(Optional.of("60"));
        when(appSettingsRepository.get("rate-limit-enabled")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("rate-limit-requests-per-minute")).thenReturn(Optional.empty());

        service.init();

        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(5);
        verify(hostClaudeService).updateMaxConcurrentCalls(2);
        assertThat(hostClaudeConfig.getTimeoutSeconds()).isEqualTo(600);
        assertThat(hostClaudeConfig.getInterCallDelayMs()).isEqualTo(100);
        assertThat(chainConfig.getWorkflowTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void validateAndApply_validValue_updatesRuntime() {
        service.validateAndApply("execution-max-concurrent", "5");

        verify(appSettingsRepository).set("execution-max-concurrent", "5");
        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(5);
    }

    @Test
    void validateAndApply_unknownKey_throws() {
        assertThatThrownBy(() -> service.validateAndApply("unknown-key", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown execution setting");
    }

    @Test
    void validateAndApply_nonNumeric_throws() {
        assertThatThrownBy(() -> service.validateAndApply("execution-max-concurrent", "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void validateAndApply_belowMin_throws() {
        assertThatThrownBy(() -> service.validateAndApply("execution-max-concurrent", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_aboveMax_throws() {
        assertThatThrownBy(() -> service.validateAndApply("execution-max-concurrent", "100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_claudeTimeout_updatesConfig() {
        service.validateAndApply("claude-timeout-seconds", "600");

        verify(appSettingsRepository).set("claude-timeout-seconds", "600");
        assertThat(hostClaudeConfig.getTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void validateAndApply_interCallDelay_updatesConfig() {
        service.validateAndApply("claude-inter-call-delay-ms", "500");

        verify(appSettingsRepository).set("claude-inter-call-delay-ms", "500");
        assertThat(hostClaudeConfig.getInterCallDelayMs()).isEqualTo(500);
    }

    @Test
    void validateAndApply_chainTimeout_updatesConfig() {
        service.validateAndApply("chain-workflow-timeout-minutes", "60");

        verify(appSettingsRepository).set("chain-workflow-timeout-minutes", "60");
        assertThat(chainConfig.getWorkflowTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void updateAll_appliesMultipleSettings() {
        service.updateAll(Map.of(
                "execution-max-concurrent", "5",
                "claude-timeout-seconds", "600"
        ));

        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(5);
        assertThat(hostClaudeConfig.getTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void getCurrentSettings_returnsAllValues() {
        when(workflowExecutor.getActiveRunCount()).thenReturn(3);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKeys(
                "executionMaxConcurrent", "claudeMaxConcurrentCalls",
                "claudeReservedForInteractive",
                "claudeTimeoutSeconds", "claudeInterCallDelayMs",
                "chainWorkflowTimeoutMinutes",
                "rateLimitEnabled", "rateLimitRequestsPerMinute",
                "allowExtraMounts",
                "activeRuns"
        );
        assertThat(settings.get("activeRuns")).isEqualTo(3);
    }

    @Test
    void validateAndApply_allowExtraMounts_setsContainerShellService() {
        service.validateAndApply("allow-extra-mounts", "1");

        verify(appSettingsRepository).set("allow-extra-mounts", "1");
        verify(containerShellService).setAllowExtraMounts(true);
    }

    @Test
    void validateAndApply_allowExtraMounts_alias_works() {
        service.validateAndApply("allowExtraMounts", "0");

        verify(appSettingsRepository).set("allow-extra-mounts", "0");
        verify(containerShellService).setAllowExtraMounts(false);
    }

    @Test
    void validateAndApply_allowExtraMounts_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("allow-extra-mounts", "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_reservedForInteractive_updatesConfig() {
        service.validateAndApply("claude-reserved-for-interactive", "2");

        verify(appSettingsRepository).set("claude-reserved-for-interactive", "2");
        verify(hostClaudeService).updateReservedForInteractive(2);
    }

    @Test
    void validateAndApply_reservedForInteractive_aboveMax_throws() {
        assertThatThrownBy(() -> service.validateAndApply("claude-reserved-for-interactive", "15"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_reservedForInteractive_belowMin_throws() {
        assertThatThrownBy(() -> service.validateAndApply("claude-reserved-for-interactive", "-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_assistantChatTimeout_updatesConfig() {
        service.validateAndApply("assistant-chat-timeout-seconds", "500");

        verify(appSettingsRepository).set("assistant-chat-timeout-seconds", "500");
        assertThat(hostClaudeConfig.getAssistantChatTimeoutSeconds()).isEqualTo(500);
    }

    @Test
    void validateAndApply_assistantChatTimeout_camelCaseAlias() {
        service.validateAndApply("assistantChatTimeoutSeconds", "300");

        verify(appSettingsRepository).set("assistant-chat-timeout-seconds", "300");
        assertThat(hostClaudeConfig.getAssistantChatTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void validateAndApply_assistantChatTimeout_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("assistant-chat-timeout-seconds", "10"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void init_ignoresOutOfBoundsDbValue() {
        when(appSettingsRepository.get("execution-max-concurrent")).thenReturn(Optional.of("999"));
        when(appSettingsRepository.get("claude-max-concurrent-calls")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-reserved-for-interactive")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-timeout-seconds")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-inter-call-delay-ms")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("chain-workflow-timeout-minutes")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("rate-limit-enabled")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("rate-limit-requests-per-minute")).thenReturn(Optional.empty());

        service.init();

        // Should keep default because 999 > 50
        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(10);
    }
}
