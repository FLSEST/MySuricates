package com.automation.service.repair;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.factory.FactoryTestResult;
import com.automation.service.factory.FactoryVerificationService;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoRepairServiceTest {

    @Mock private FactoryVerificationService factoryVerificationService;
    @Mock private WorkflowService workflowService;
    @Mock private TestingService testingService;
    @Mock private ActivityLogService activityLogService;
    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ExecutorService virtualThreadExecutor;

    private AutoRepairService service;

    @BeforeEach
    void setUp() {
        service = new AutoRepairService(
                factoryVerificationService, workflowService, testingService,
                activityLogService, appSettingsRepository, workflowRunRepository,
                workflowExecutor, virtualThreadExecutor);
        // Default: the AI repair was saved (99a9dfb0); the discarded case stubs its own outcome.
        try {
            lenient().when(factoryVerificationService.autoFixWorkflow(any(), anyString()))
                    .thenReturn(new FactoryVerificationService.AutoFixOutcome(true, null));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Event listener tests ──────────────────────────────

    @Test
    void onWorkflowRunCompleted_skipsNonFailedRuns() {
        var event = createEvent(RunStatus.COMPLETED, Map.of(), Map.of());
        service.onWorkflowRunCompleted(event);
        verifyNoInteractions(factoryVerificationService, workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_skipsRepairAttempts() {
        var event = createEvent(RunStatus.FAILED, Map.of("autoRepairAttempt", true), Map.of());
        service.onWorkflowRunCompleted(event);
        verifyNoInteractions(factoryVerificationService, workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_skipsWhenDisabled() {
        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", false)));
        service.onWorkflowRunCompleted(event);
        verifyNoInteractions(factoryVerificationService, workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_skipsTestModeWhenTestUnhealthy() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true, "mode", "test")));
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(testingService.isHealthy()).thenReturn(false);

        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true, "mode", "test")));
        service.onWorkflowRunCompleted(event);

        verifyNoInteractions(factoryVerificationService, workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_skipsSealedWorkflowsBeforeAnyAiCall() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true, "mode", "local")));
        workflow.setSealed(true);
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true, "mode", "local")));
        service.onWorkflowRunCompleted(event);

        verifyNoInteractions(factoryVerificationService, workflowExecutor, activityLogService);
    }

    @Test
    void repairAllFailed_skipsSealedWorkflowsEvenWhenForced() {
        var sealed = createWorkflow(Map.of());
        sealed.setSealed(true);
        when(workflowRunRepository.findDistinctWorkflowIdsWithFailedRuns()).thenReturn(java.util.List.of("wf-1"));
        when(workflowService.findById("wf-1")).thenReturn(sealed);

        assertThat(service.repairAllFailed(true)).isZero();

        verifyNoInteractions(factoryVerificationService, workflowExecutor);
    }

    // ── Retroactive repairs run in the background, not in the request (TODO d04398a3) ──

    @Test
    void repairAllFailed_returnsAtOnce_andEachRepairRunsOnTheExecutor() throws Exception {
        when(workflowRunRepository.findDistinctWorkflowIdsWithFailedRuns()).thenReturn(List.of("wf-1", "wf-2"));
        when(workflowService.findById(anyString())).thenReturn(createWorkflow(Map.of("autoRepair", Map.of("mode", "test"))));
        when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                .thenReturn(new FactoryTestResult("run-x", "PASSED", null));
        ArgumentCaptor<Runnable> repairs = ArgumentCaptor.forClass(Runnable.class);

        assertThat(service.repairAllFailed(true)).isEqualTo(2);

        verify(virtualThreadExecutor, times(2)).execute(repairs.capture());
        verifyNoInteractions(factoryVerificationService, workflowExecutor);

        repairs.getAllValues().forEach(Runnable::run);
        verify(factoryVerificationService).verifyAndFix("wf-1", 2);
        verify(factoryVerificationService).verifyAndFix("wf-2", 2);
    }

    @Test
    void repairAllFailed_doesNotCountARepairTheExecutorRejects() {
        when(workflowRunRepository.findDistinctWorkflowIdsWithFailedRuns()).thenReturn(List.of("wf-1"));
        when(workflowService.findById("wf-1")).thenReturn(createWorkflow(Map.of()));
        doThrow(new RejectedExecutionException("shut down")).when(virtualThreadExecutor).execute(any());

        assertThat(service.repairAllFailed(true)).isZero();
    }

    @Test
    void repairWorkflow_onlyHandsTheRepairToTheExecutor() {
        service.repairWorkflow("wf-1");

        verify(virtualThreadExecutor).execute(any(Runnable.class));
        verifyNoInteractions(workflowService, factoryVerificationService, workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_usesTestWhenAutoModeAndTestHealthy() throws Exception {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true)));
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(testingService.isHealthy()).thenReturn(true);
        when(factoryVerificationService.verifyAndFix("wf-1", 2))
                .thenReturn(new FactoryTestResult("run-1", "PASSED", null));

        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true)));
        service.onWorkflowRunCompleted(event);

        verify(factoryVerificationService).verifyAndFix("wf-1", 2);
        verifyNoInteractions(workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_usesLocalWhenAutoModeAndTestUnhealthy() throws Exception {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true)));
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(testingService.isHealthy()).thenReturn(false);
        when(appSettingsRepository.get("auto-repair-mode")).thenReturn(Optional.empty());

        // Local repair: autoFix + execute + wait
        var completedRun = WorkflowRun.builder().status(RunStatus.COMPLETED).build();
        completedRun.setId("repair-run-1");
        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR))).thenReturn(completedRun);
        when(workflowRunRepository.findById("repair-run-1")).thenReturn(Optional.of(completedRun));

        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true)));
        service.onWorkflowRunCompleted(event);

        verify(factoryVerificationService).autoFixWorkflow(any(Workflow.class), anyString());
        verify(workflowExecutor).execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR));
    }

    @Test
    void onWorkflowRunCompleted_usesLocalWhenLocalMode() throws Exception {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true, "mode", "local")));
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var completedRun = WorkflowRun.builder().status(RunStatus.COMPLETED).build();
        completedRun.setId("repair-run-1");
        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR))).thenReturn(completedRun);
        when(workflowRunRepository.findById("repair-run-1")).thenReturn(Optional.of(completedRun));

        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true, "mode", "local")));
        service.onWorkflowRunCompleted(event);

        verify(factoryVerificationService).autoFixWorkflow(any(Workflow.class), anyString());
        verify(workflowExecutor).execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR));
        verify(factoryVerificationService, never()).verifyAndFix(anyString(), anyInt());
    }

    // ── resolveRepairMode tests ──────────────────────────────

    @Test
    void resolveRepairMode_usesWorkflowMetadata() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("mode", "local")));
        assertThat(service.resolveRepairMode(workflow)).isEqualTo("local");
    }

    @Test
    void resolveRepairMode_usesWorkflowMetadata_test() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("mode", "test")));
        assertThat(service.resolveRepairMode(workflow)).isEqualTo("test");
    }

    @Test
    void resolveRepairMode_fallsBackToGlobalSetting() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-mode")).thenReturn(Optional.of("local"));
        assertThat(service.resolveRepairMode(workflow)).isEqualTo("local");
    }

    @Test
    void resolveRepairMode_defaultsToAuto() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-mode")).thenReturn(Optional.empty());
        assertThat(service.resolveRepairMode(workflow)).isEqualTo("auto");
    }

    @Test
    void resolveRepairMode_ignoresInvalidValue() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("mode", "invalid")));
        when(appSettingsRepository.get("auto-repair-mode")).thenReturn(Optional.empty());
        assertThat(service.resolveRepairMode(workflow)).isEqualTo("auto");
    }

    // ── isAutoRepairEnabled tests ──────────────────────────────

    @Test
    void isAutoRepairEnabled_explicitTrue() {
        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true)));
        assertThat(service.isAutoRepairEnabled(event)).isTrue();
    }

    @Test
    void isAutoRepairEnabled_explicitFalse() {
        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", false)));
        assertThat(service.isAutoRepairEnabled(event)).isFalse();
    }

    @Test
    void isAutoRepairEnabled_fallsBackToGlobalDefault() {
        var event = createEvent(RunStatus.FAILED, Map.of(), Map.of());
        when(appSettingsRepository.get("auto-repair-default")).thenReturn(Optional.of("true"));
        assertThat(service.isAutoRepairEnabled(event)).isTrue();
    }

    @Test
    void isAutoRepairEnabled_defaultFalse() {
        var event = createEvent(RunStatus.FAILED, Map.of(), Map.of());
        when(appSettingsRepository.get("auto-repair-default")).thenReturn(Optional.empty());
        assertThat(service.isAutoRepairEnabled(event)).isFalse();
    }

    // ── resolveMaxRetries tests ──────────────────────────────

    @Test
    void resolveMaxRetries_usesWorkflowMetadata() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("maxRetries", 4)));
        assertThat(service.resolveMaxRetries(workflow)).isEqualTo(4);
    }

    @Test
    void resolveMaxRetries_fallsBackToGlobalSetting() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-max-retries")).thenReturn(Optional.of("3"));
        assertThat(service.resolveMaxRetries(workflow)).isEqualTo(3);
    }

    @Test
    void resolveMaxRetries_defaultsTo2() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-max-retries")).thenReturn(Optional.empty());
        assertThat(service.resolveMaxRetries(workflow)).isEqualTo(2);
    }

    @Test
    void resolveMaxRetries_clampsToRange() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("maxRetries", 10)));
        when(appSettingsRepository.get("auto-repair-max-retries")).thenReturn(Optional.empty());
        // Out of range (>5), falls back to global/default
        assertThat(service.resolveMaxRetries(workflow)).isEqualTo(2);
    }

    // ── resolveTimeoutSeconds tests ──────────────────────────────

    @Test
    void resolveTimeoutSeconds_usesWorkflowMetadata() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("timeoutSeconds", 300)));
        assertThat(service.resolveTimeoutSeconds(workflow)).isEqualTo(300);
    }

    @Test
    void resolveTimeoutSeconds_fallsBackToGlobalSetting() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-timeout-seconds")).thenReturn(Optional.of("900"));
        assertThat(service.resolveTimeoutSeconds(workflow)).isEqualTo(900);
    }

    @Test
    void resolveTimeoutSeconds_defaultsTo600() {
        var workflow = createWorkflow(Map.of());
        when(appSettingsRepository.get("auto-repair-timeout-seconds")).thenReturn(Optional.empty());
        assertThat(service.resolveTimeoutSeconds(workflow)).isEqualTo(600);
    }

    @Test
    void resolveTimeoutSeconds_clampsToRange() {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("timeoutSeconds", 10)));
        when(appSettingsRepository.get("auto-repair-timeout-seconds")).thenReturn(Optional.empty());
        // Out of range (<60), falls back to global/default
        assertThat(service.resolveTimeoutSeconds(workflow)).isEqualTo(600);
    }

    // ── repairLocally tests ──────────────────────────────

    @Test
    void repairLocally_passesOnFirstAttempt() throws Exception {
        var workflow = createWorkflow(Map.of());
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var completedRun = WorkflowRun.builder().status(RunStatus.COMPLETED).build();
        completedRun.setId("run-1");
        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR))).thenReturn(completedRun);
        when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(completedRun));

        FactoryTestResult result = service.repairLocally("wf-1", "some error", 2, 600);

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.runId()).isEqualTo("run-1");
        verify(factoryVerificationService, times(1)).autoFixWorkflow(any(), anyString());
    }

    @Test
    void repairLocally_retriesOnFailure() throws Exception {
        var workflow = createWorkflow(Map.of());
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var failedRun = WorkflowRun.builder().status(RunStatus.FAILED).build();
        failedRun.setId("run-fail");
        failedRun.setError(Map.of("message", "still broken"));

        var passedRun = WorkflowRun.builder().status(RunStatus.COMPLETED).build();
        passedRun.setId("run-pass");

        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR)))
                .thenReturn(failedRun)
                .thenReturn(passedRun);
        when(workflowRunRepository.findById("run-fail")).thenReturn(Optional.of(failedRun));
        when(workflowRunRepository.findById("run-pass")).thenReturn(Optional.of(passedRun));

        FactoryTestResult result = service.repairLocally("wf-1", "original error", 2, 600);

        assertThat(result.status()).isEqualTo("PASSED");
        verify(factoryVerificationService, times(2)).autoFixWorkflow(any(), anyString());
    }

    @Test
    void repairLocally_failsAfterMaxRetries() throws Exception {
        var workflow = createWorkflow(Map.of());
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var failedRun = WorkflowRun.builder().status(RunStatus.FAILED).build();
        failedRun.setId("run-fail");
        failedRun.setError(Map.of("message", "still broken"));

        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR))).thenReturn(failedRun);
        when(workflowRunRepository.findById("run-fail")).thenReturn(Optional.of(failedRun));

        FactoryTestResult result = service.repairLocally("wf-1", "original error", 2, 600);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).isEqualTo("still broken");
        verify(factoryVerificationService, times(2)).autoFixWorkflow(any(), anyString());
    }

    @Test
    void repairLocally_discardedRepair_doesNotRunTheUnchangedWorkflow_andReportsTheReason() throws Exception {
        // TODO 99a9dfb0: a repair the build check discards leaves the workflow unchanged — running it again
        // would only repeat the failure (and its AI calls).
        var workflow = createWorkflow(Map.of());
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(factoryVerificationService.autoFixWorkflow(any(), anyString())).thenReturn(
                new FactoryVerificationService.AutoFixOutcome(false, "Reparatur verworfen, Struktur geändert: Blockzahl 2 → 1"));

        FactoryTestResult result = service.repairLocally("wf-1", "original error", 2, 600);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.runId()).isNull();
        assertThat(result.error()).contains("original error").contains("Reparatur verworfen, Struktur geändert: Blockzahl 2 → 1");
        verify(factoryVerificationService, times(2)).autoFixWorkflow(any(), eq("original error"));
        verifyNoInteractions(workflowExecutor);
    }

    @Test
    void onWorkflowRunCompleted_discardedLocalRepair_reasonLandsInTheActivityLog() throws Exception {
        var workflow = createWorkflow(Map.of("autoRepair", Map.of("enabled", true, "mode", "local", "maxRetries", 1)));
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(factoryVerificationService.autoFixWorkflow(any(), anyString())).thenReturn(
                new FactoryVerificationService.AutoFixOutcome(false, "Reparatur verworfen, mehr HIGH-Befunde (0 → 1)"));
        var event = createEvent(RunStatus.FAILED, Map.of(),
                Map.of("autoRepair", Map.of("enabled", true, "mode", "local", "maxRetries", 1)));

        service.onWorkflowRunCompleted(event);

        verifyNoInteractions(workflowExecutor);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION),
                contains("Reparatur verworfen, mehr HIGH-Befunde (0 → 1)"), eq("AutoRepairService"), any(), eq("wf-1"));
    }

    @Test
    void repairLocally_setsAutoRepairAttemptMetadata() throws Exception {
        var workflow = createWorkflow(Map.of());
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        var completedRun = WorkflowRun.builder().status(RunStatus.COMPLETED).build();
        completedRun.setId("run-1");
        when(workflowExecutor.execute(any(Workflow.class), anyMap(), isNull(), anyMap(), eq(TriggerSource.AUTO_REPAIR))).thenReturn(completedRun);
        when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(completedRun));

        service.repairLocally("wf-1", "error", 1, 600);

        verify(workflowExecutor).execute(any(Workflow.class), anyMap(), isNull(), argThat(meta ->
                Boolean.TRUE.equals(meta.get("autoRepairAttempt"))), eq(TriggerSource.AUTO_REPAIR));
    }

    // ── Helpers ──────────────────────────────

    private WorkflowRunCompletedEvent createEvent(RunStatus status,
                                                   Map<String, Object> runMetadata,
                                                   Map<String, Object> workflowMetadata) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow",
                "proj-1", "/tmp/test", status,
                new HashMap<>(runMetadata), new HashMap<>(workflowMetadata),
                Map.of("message", "Test error"));
    }

    private Workflow createWorkflow(Map<String, Object> metadata) {
        var workflow = new Workflow();
        workflow.setId("wf-1");
        workflow.setName("Test Workflow");
        workflow.setMetadata(new HashMap<>(metadata));
        workflow.setDefaultContext(new HashMap<>());
        return workflow;
    }
}
