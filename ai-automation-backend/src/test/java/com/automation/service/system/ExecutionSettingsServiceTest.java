package com.automation.service.system;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.config.ChainConfig;
import com.automation.config.DashboardReportAssistConfig;
import com.automation.config.CompressionConfig;
import com.automation.config.FactoryConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.config.RateLimitConfig;
import com.automation.config.RequestMetricsConfig;
import com.automation.config.SchedulerConfig;
import com.automation.config.SummarizationConfig;
import com.automation.config.TaskCommandConfig;
import com.automation.config.AssistantGuardrailsConfig;
import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.config.TodoHygieneConfig;
import com.automation.config.TodoSimilarityConfig;
import com.automation.config.RunRetentionConfig;
import com.automation.config.DashboardLayoutConfig;
import com.automation.config.WishConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.cli.ContainerShellService;
import com.automation.exception.HardeningException;
import com.automation.service.system.settings.AccessLimitSettings;
import com.automation.service.system.settings.ExecutionCapacitySettings;
import com.automation.service.system.settings.FeatureSettings;
import com.automation.service.system.settings.HousekeepingSettings;
import com.automation.service.system.settings.RequestMetricsSettings;
import com.automation.service.system.settings.RuntimeSettingsGroup;
import com.automation.service.workflow.RunDeletionPolicy;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionSettingsServiceTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private HostClaudeService hostClaudeService;
    @Mock private WorkflowExecutionRegistry executionRegistry;
    @Mock private ContainerShellService containerShellService;
    @Mock private com.automation.service.ai.catalog.ModelCatalogService modelCatalogService;

    private DynamicSemaphore executionSemaphore;
    private HostClaudeConfig hostClaudeConfig;
    private ChainConfig chainConfig;
    private RateLimitConfig rateLimitConfig;
    private SchedulerConfig schedulerConfig;
    private TodoHygieneConfig todoHygieneConfig;
    private TodoGhostWatchdogConfig todoGhostWatchdogConfig;
    private AssistantGuardrailsConfig assistantGuardrailsConfig;
    private TaskCommandConfig taskCommandConfig;
    private SummarizationConfig summarizationConfig;
    private FactoryConfig factoryConfig;
    private WishConfig wishConfig;
    private RunRetentionConfig runRetentionConfig;
    private DashboardLayoutConfig dashboardLayoutConfig;
    private CompressionConfig compressionConfig;
    private TodoSimilarityConfig todoSimilarityConfig;
    private RequestMetricsConfig requestMetricsConfig;
    private final DashboardReportAssistConfig dashboardReportAssistConfig = new DashboardReportAssistConfig();
    private ExecutionSettingsService service;

    @BeforeEach
    void setUp() {
        executionSemaphore = new DynamicSemaphore(10);
        hostClaudeConfig = new HostClaudeConfig();
        chainConfig = new ChainConfig();
        rateLimitConfig = new RateLimitConfig();
        schedulerConfig = new SchedulerConfig();
        todoHygieneConfig = new TodoHygieneConfig();
        todoGhostWatchdogConfig = new TodoGhostWatchdogConfig();
        assistantGuardrailsConfig = new AssistantGuardrailsConfig();
        taskCommandConfig = new TaskCommandConfig();
        summarizationConfig = new SummarizationConfig();
        factoryConfig = new FactoryConfig();
        wishConfig = new WishConfig();
        runRetentionConfig = new RunRetentionConfig();
        dashboardLayoutConfig = new DashboardLayoutConfig();
        compressionConfig = new CompressionConfig();
        todoSimilarityConfig = new TodoSimilarityConfig();
        requestMetricsConfig = new RequestMetricsConfig();

        service = newService(new RunDeletionPolicy(true));
    }

    /** Die fuenf Themen-Gruppen so, wie Spring sie in @Order-Reihenfolge injiziert. */
    private ExecutionSettingsService newService(RunDeletionPolicy runDeletionPolicy) {
        return new ExecutionSettingsService(appSettingsRepository, List.of(
                new ExecutionCapacitySettings(executionSemaphore, hostClaudeService, hostClaudeConfig,
                        chainConfig, taskCommandConfig, executionRegistry),
                new AccessLimitSettings(rateLimitConfig, containerShellService, assistantGuardrailsConfig),
                new HousekeepingSettings(todoHygieneConfig, runRetentionConfig, dashboardLayoutConfig,
                        todoGhostWatchdogConfig, todoSimilarityConfig, runDeletionPolicy),
                new FeatureSettings(schedulerConfig, summarizationConfig, factoryConfig, wishConfig,
                        modelCatalogService, compressionConfig, dashboardReportAssistConfig),
                new RequestMetricsSettings(requestMetricsConfig)));
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
        when(executionRegistry.getActiveRunCount()).thenReturn(3);

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
    void validateAndApply_taskCommandTimeout_updatesConfig() {
        service.validateAndApply("task-command-timeout-seconds", "120");

        verify(appSettingsRepository).set("task-command-timeout-seconds", "120");
        assertThat(taskCommandConfig.getTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void validateAndApply_taskCommandTimeout_camelCaseAlias() {
        service.validateAndApply("taskCommandTimeoutSeconds", "90");

        verify(appSettingsRepository).set("task-command-timeout-seconds", "90");
        assertThat(taskCommandConfig.getTimeoutSeconds()).isEqualTo(90);
    }

    @Test
    void validateAndApply_taskCommandTimeout_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("task-command-timeout-seconds", "5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_todoHygieneEnabled_updatesConfig() {
        service.validateAndApply("todo-hygiene-enabled", "1");

        verify(appSettingsRepository).set("todo-hygiene-enabled", "1");
        assertThat(todoHygieneConfig.isEnabled()).isTrue();
    }

    @Test
    void validateAndApply_todoStaleDays_camelCaseAlias_updatesConfig() {
        service.validateAndApply("todoStaleDays", "45");

        verify(appSettingsRepository).set("todo-stale-days", "45");
        assertThat(todoHygieneConfig.getStaleDays()).isEqualTo(45);
    }

    @Test
    void validateAndApply_todoStaleDays_belowMin_throws() {
        assertThatThrownBy(() -> service.validateAndApply("todo-stale-days", "3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void validateAndApply_todoHygieneMaxPerRun_updatesConfig() {
        service.validateAndApply("todo-hygiene-max-per-run", "500");

        verify(appSettingsRepository).set("todo-hygiene-max-per-run", "500");
        assertThat(todoHygieneConfig.getMaxPerRun()).isEqualTo(500);
    }

    @Test
    void validateAndApply_schedulerCatchUpOnResume_disables_updatesConfig() {
        service.validateAndApply("scheduler-catch-up-on-resume", "0");

        verify(appSettingsRepository).set("scheduler-catch-up-on-resume", "0");
        assertThat(schedulerConfig.isCatchUpOnResume()).isFalse();
    }

    @Test
    void validateAndApply_schedulerCatchUpOnResume_camelCaseAlias_enables_updatesConfig() {
        service.validateAndApply("schedulerCatchUpOnResume", "1");

        verify(appSettingsRepository).set("scheduler-catch-up-on-resume", "1");
        assertThat(schedulerConfig.isCatchUpOnResume()).isTrue();
    }

    @Test
    void getCurrentSettings_includesSchedulerCatchUpOnResume() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKey("schedulerCatchUpOnResume");
        assertThat(settings.get("schedulerCatchUpOnResume")).isEqualTo(1); // default is true
    }

    // --- Gruppe A: Scheduler resume-grace-minutes ---

    @Test
    void validateAndApply_schedulerResumeGraceMinutes_updatesConfig() {
        service.validateAndApply("scheduler-resume-grace-minutes", "10");

        verify(appSettingsRepository).set("scheduler-resume-grace-minutes", "10");
        assertThat(schedulerConfig.getResumeGraceMinutes()).isEqualTo(10);
    }

    @Test
    void validateAndApply_schedulerResumeGraceMinutes_camelCaseAlias() {
        service.validateAndApply("schedulerResumeGraceMinutes", "3");

        verify(appSettingsRepository).set("scheduler-resume-grace-minutes", "3");
        assertThat(schedulerConfig.getResumeGraceMinutes()).isEqualTo(3);
    }

    @Test
    void validateAndApply_schedulerResumeGraceMinutes_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("scheduler-resume-grace-minutes", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void getCurrentSettings_includesSchedulerResumeGraceMinutes() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKey("schedulerResumeGraceMinutes");
        assertThat(settings.get("schedulerResumeGraceMinutes")).isEqualTo(5L); // default
    }

    // --- Gruppe C: System-Timeouts ---

    @Test
    void validateAndApply_summarizationTimeoutSeconds_updatesConfig() {
        service.validateAndApply("summarization-timeout-seconds", "240");

        verify(appSettingsRepository).set("summarization-timeout-seconds", "240");
        assertThat(summarizationConfig.getTimeoutSeconds()).isEqualTo(240);
    }

    @Test
    void validateAndApply_summarizationTimeout_camelCaseAlias() {
        service.validateAndApply("summarizationTimeoutSeconds", "180");

        verify(appSettingsRepository).set("summarization-timeout-seconds", "180");
        assertThat(summarizationConfig.getTimeoutSeconds()).isEqualTo(180);
    }

    @Test
    void validateAndApply_summarizationTimeout_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("summarization-timeout-seconds", "10"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void getCurrentSettings_includesSummarizationTimeoutSeconds() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKey("summarizationTimeoutSeconds");
        assertThat(settings.get("summarizationTimeoutSeconds")).isEqualTo(120); // default
    }

    @Test
    void validateAndApply_factoryTimeoutSeconds_updatesConfig() {
        service.validateAndApply("factory-timeout-seconds", "300");

        verify(appSettingsRepository).set("factory-timeout-seconds", "300");
        assertThat(factoryConfig.getTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void validateAndApply_factoryTimeout_camelCaseAlias() {
        service.validateAndApply("factoryTimeoutSeconds", "900");

        verify(appSettingsRepository).set("factory-timeout-seconds", "900");
        assertThat(factoryConfig.getTimeoutSeconds()).isEqualTo(900);
    }

    @Test
    void validateAndApply_factoryTimeout_outOfBounds_throws() {
        assertThatThrownBy(() -> service.validateAndApply("factory-timeout-seconds", "30"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between");
    }

    @Test
    void getCurrentSettings_includesFactoryTimeoutSeconds() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKey("factoryTimeoutSeconds");
        assertThat(settings.get("factoryTimeoutSeconds")).isEqualTo(600); // default
    }

    // --- Gruppe B: WishConfig ---

    @Test
    void validateAndApply_wishTotalTimeoutMinutes_updatesConfig() {
        service.validateAndApply("wish-total-timeout-minutes", "60");

        verify(appSettingsRepository).set("wish-total-timeout-minutes", "60");
        assertThat(wishConfig.getTotalTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void validateAndApply_wishTotalTimeout_camelCaseAlias() {
        service.validateAndApply("wishTotalTimeoutMinutes", "90");

        verify(appSettingsRepository).set("wish-total-timeout-minutes", "90");
        assertThat(wishConfig.getTotalTimeoutMinutes()).isEqualTo(90);
    }

    @Test
    void validateAndApply_wishWorkflowWaitMinutes_updatesConfig() {
        service.validateAndApply("wish-workflow-wait-minutes", "15");

        verify(appSettingsRepository).set("wish-workflow-wait-minutes", "15");
        assertThat(wishConfig.getWorkflowWaitMinutes()).isEqualTo(15);
    }

    @Test
    void validateAndApply_wishDefaultShellTimeoutSeconds_updatesConfig() {
        service.validateAndApply("wish-default-shell-timeout-seconds", "1200");

        verify(appSettingsRepository).set("wish-default-shell-timeout-seconds", "1200");
        assertThat(wishConfig.getDefaultShellTimeoutSeconds()).isEqualTo(1200);
    }

    @Test
    void validateAndApply_wishDefaultAgenticTotalTimeoutSeconds_updatesConfig() {
        service.validateAndApply("wish-default-agentic-total-timeout-seconds", "7200");

        verify(appSettingsRepository).set("wish-default-agentic-total-timeout-seconds", "7200");
        assertThat(wishConfig.getDefaultAgenticTotalTimeoutSeconds()).isEqualTo(7200);
    }

    @Test
    void getCurrentSettings_includesWishSettings() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        Map<String, Object> settings = service.getCurrentSettings();

        assertThat(settings).containsKeys(
                "wishTotalTimeoutMinutes",
                "wishWorkflowWaitMinutes",
                "wishDefaultShellTimeoutSeconds",
                "wishDefaultAgenticTotalTimeoutSeconds"
        );
        assertThat(settings.get("wishTotalTimeoutMinutes")).isEqualTo(120);
        assertThat(settings.get("wishWorkflowWaitMinutes")).isEqualTo(30);
        assertThat(settings.get("wishDefaultShellTimeoutSeconds")).isEqualTo(600);
        assertThat(settings.get("wishDefaultAgenticTotalTimeoutSeconds")).isEqualTo(3600);
    }

    @Test
    void validateAndApply_everyKnownKey_hasRuntimeApplier() {
        ExecutionSettingKeys.BOUNDS.forEach((key, bound) ->
                service.validateAndApply(key, bound.format(bound.min())));

        ExecutionSettingKeys.BOUNDS.forEach((key, bound) ->
                verify(appSettingsRepository).set(key, bound.format(bound.min())));
    }

    @Test
    void todoSimilarityKeywordThreshold_defaultsToTwoTenths() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        assertThat(todoSimilarityConfig.getKeywordThreshold()).isEqualTo(0.2);
        assertThat(service.getCurrentSettings()).containsEntry("todoSimilarityKeywordThreshold", 0.2);
    }

    @Test
    void validateAndApply_todoSimilarityKeywordThreshold_updatesConfig() {
        service.validateAndApply("todoSimilarityKeywordThreshold", "0.45");

        verify(appSettingsRepository).set("todo-similarity-keyword-threshold", "0.45");
        assertThat(todoSimilarityConfig.getKeywordThreshold()).isEqualTo(0.45);
    }

    @Test
    void validateAndApply_todoSimilarityKeywordThresholdOutOfBounds_isRejected() {
        assertThatThrownBy(() -> service.validateAndApply("todo-similarity-keyword-threshold", "0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between 0.05 and 0.9");

        assertThat(todoSimilarityConfig.getKeywordThreshold()).isEqualTo(0.2);
        verify(appSettingsRepository, never()).set(eq("todo-similarity-keyword-threshold"), anyString());
    }

    @Test
    void init_appliesTodoSimilarityKeywordThresholdFromDb() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());
        when(appSettingsRepository.get("todo-similarity-keyword-threshold")).thenReturn(Optional.of("0.5"));

        service.init();

        assertThat(todoSimilarityConfig.getKeywordThreshold()).isEqualTo(0.5);
    }

    /**
     * init() laeuft in @PostConstruct: wirft ein Applier beim Anwenden eines gespeicherten Wertes,
     * darf das den Start nicht verhindern — der Wert wird verworfen, alles andere laeuft weiter.
     */
    @Test
    void init_aFailingApplierDoesNotBreakTheStartup() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-max-concurrent-calls")).thenReturn(Optional.of("7"));
        when(appSettingsRepository.get("claude-timeout-seconds")).thenReturn(Optional.of("600"));
        doThrow(new IllegalStateException("semaphore busy")).when(hostClaudeService).updateMaxConcurrentCalls(7);

        assertThatCode(() -> service.init()).doesNotThrowAnyException();

        assertThat(hostClaudeConfig.getTimeoutSeconds()).as("die uebrigen Werte greifen trotzdem").isEqualTo(600);
    }

    @Test
    void init_ignoresAnOutOfBoundsTodoSimilarityKeywordThreshold() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());
        when(appSettingsRepository.get("todo-similarity-keyword-threshold")).thenReturn(Optional.of("5"));

        service.init();

        assertThat(todoSimilarityConfig.getKeywordThreshold()).isEqualTo(0.2);
    }

    @Test
    void init_appliesDashboardLayoutRetentionFromDb() {
        when(appSettingsRepository.get(anyString())).thenReturn(Optional.empty());
        when(appSettingsRepository.get("dashboard-layout-retention-count")).thenReturn(Optional.of("42"));

        service.init();

        assertThat(dashboardLayoutConfig.getRetentionCount()).isEqualTo(42);
        assertThat(service.getCurrentSettings()).containsEntry("dashboardLayoutRetentionCount", 42);
    }

    @Test
    void init_ignoresOutOfBoundsDbValue() {
        when(appSettingsRepository.get("execution-max-concurrent")).thenReturn(Optional.of("999"));
        when(appSettingsRepository.get("claude-max-concurrent-calls")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-reserved-for-interactive")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-timeout-seconds")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("claude-inter-call-delay-ms")).thenReturn(Optional.empty());
        when(appSettingsRepository.get("chain-workflow-timeout-minutes")).thenReturn(Optional.empty());

        service.init();

        // Should keep default because 999 > 50
        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(10);
    }

    @Test
    void dashboardReportAssist_isOnByDefault_andCanBeSwitchedOff() {
        when(executionRegistry.getActiveRunCount()).thenReturn(0);

        assertThat(service.getCurrentSettings()).containsEntry("dashboardReportAssistEnabled", 1);

        service.validateAndApply("dashboardReportAssistEnabled", "0");

        verify(appSettingsRepository).set("dashboard-report-assist-enabled", "0");
        assertThat(dashboardReportAssistConfig.isEnabled()).isFalse();
        assertThat(service.getCurrentSettings()).containsEntry("dashboardReportAssistEnabled", 0);
    }

    @Test
    void dashboardReportAssist_rejectsAnythingButZeroAndOne() {
        assertThatThrownBy(() -> service.validateAndApply("dashboard-report-assist-enabled", "2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard-report-assist-enabled");
    }

    /**
     * Ein Applier ohne Grenzen in BOUNDS ist tot: {@code validateAndApply} loest den Schluessel
     * zuerst ueber {@code resolve} auf, das fuer unbekannte Schluessel wirft. Genau so waren die
     * sieben {@code request-metrics-*}-Einstellungen unerreichbar (TODO fc0994d6) — dieser
     * Waechter faengt den naechsten Fall beim Anlegen.
     */
    @Test
    void jederApplierHatGrenzenUndIstDamitErreichbar() {
        assertThat(service.applierKeys())
                .allSatisfy(key -> assertThat(ExecutionSettingKeys.BOUNDS)
                        .as("Applier '%s' ohne Eintrag in BOUNDS — ueber die API nicht erreichbar", key)
                        .containsKey(key));
    }

    /** Und umgekehrt: eine Grenze ohne Applier verspricht eine Wirkung, die niemand ausfuehrt. */
    @Test
    void jederGrenzwertHatEinenApplier() {
        assertThat(ExecutionSettingKeys.BOUNDS.keySet())
                .allSatisfy(key -> assertThat(service.applierKeys())
                        .as("Grenzwert '%s' ohne Applier — der Wert wird gespeichert, wirkt aber nicht", key)
                        .contains(key));
    }

    @Test
    void sameKeyInTwoGroups_isRefusedAtConstruction() {
        RuntimeSettingsGroup duplicate = new RuntimeSettingsGroup() {
            @Override public Map<String, java.util.function.Consumer<String>> appliers() {
                return Map.of("execution-max-concurrent", v -> { });
            }
            @Override public void currentValues(Map<String, Object> out) { }
        };

        assertThatThrownBy(() -> new ExecutionSettingsService(appSettingsRepository, List.of(
                new ExecutionCapacitySettings(executionSemaphore, hostClaudeService, hostClaudeConfig,
                        chainConfig, taskCommandConfig, executionRegistry),
                duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-max-concurrent");
    }

    /** Ein Batch wird vorab komplett geprueft — ein ungueltiger Wert laesst auch die gueltigen davor ungesetzt. */
    @Test
    void batchWithInvalidValue_appliesNothing() {
        LinkedHashMap<String, String> batch = new LinkedHashMap<>();
        batch.put("execution-max-concurrent", "3");
        batch.put("claude-timeout-seconds", "-1");

        assertThatThrownBy(() -> service.updateAll(batch)).isInstanceOf(IllegalArgumentException.class);

        assertThat(executionSemaphore.getMaxPermits()).isEqualTo(10);
        verify(appSettingsRepository, never()).set(anyString(), anyString());
    }

    /** 8dbd7e10: a leaked key must not delete runs through a shortened retention. */
    @Nested
    class RetentionWhileDeletionIsDisabled {

        private ExecutionSettingsService locked;

        @BeforeEach
        void lock() {
            runRetentionConfig.setRetentionDays(90);
            locked = newService(new RunDeletionPolicy(false));
        }

        @Test
        void shorterRetention_isRefusedAndNothingIsStored() {
            assertThatThrownBy(() -> locked.validateAndApply("run-retention-days", "7"))
                    .isInstanceOf(HardeningException.class);

            assertThat(runRetentionConfig.getRetentionDays()).isEqualTo(90);
            verify(appSettingsRepository, never()).set(eq("run-retention-days"), anyString());
        }

        @Test
        void camelCaseAlias_isGuardedToo() {
            assertThatThrownBy(() -> locked.updateAll(Map.of("runRetentionDays", "30")))
                    .isInstanceOf(HardeningException.class);
            assertThat(runRetentionConfig.getRetentionDays()).isEqualTo(90);
        }

        @Test
        void refusedBatch_appliesNoOtherKeyEither() {
            LinkedHashMap<String, String> batch = new LinkedHashMap<>();
            batch.put("execution-max-concurrent", "3");
            batch.put("run-retention-days", "1");

            assertThatThrownBy(() -> locked.updateAll(batch)).isInstanceOf(HardeningException.class);

            assertThat(executionSemaphore.getMaxPermits()).isEqualTo(10);
            verify(appSettingsRepository, never()).set(anyString(), anyString());
        }

        @Test
        void longerRetentionOrOff_isAccepted() {
            locked.validateAndApply("run-retention-days", "120");
            assertThat(runRetentionConfig.getRetentionDays()).isEqualTo(120);

            locked.validateAndApply("run-retention-days", "0");
            assertThat(runRetentionConfig.getRetentionDays()).isZero();
        }

        @Test
        void fromOff_everyPositiveValueIsAShortening() {
            runRetentionConfig.setRetentionDays(0);

            assertThatThrownBy(() -> locked.validateAndApply("run-retention-days", "730"))
                    .isInstanceOf(HardeningException.class);
            assertThat(runRetentionConfig.getRetentionDays()).isZero();
        }
    }

    @Test
    void shorterRetention_isFreeWhileDeletionIsAllowed() {
        runRetentionConfig.setRetentionDays(90);

        service.validateAndApply("run-retention-days", "7");

        assertThat(runRetentionConfig.getRetentionDays()).isEqualTo(7);
    }
}
