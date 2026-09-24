package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Run control on its own: pause/resume/cancel/approval, ad-hoc commands and the startup cleanup
 * against a real {@link WorkflowExecutionRegistry}. How the engine reacts to a cancel (queued, during
 * the container start, mid-run) is pinned in {@code WorkflowExecutorTest}, which drives the cancel
 * through this service.
 */
@ExtendWith(MockitoExtension.class)
class RunControlServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private ActivityLogService activityLogService;
    @Mock private CommandExecutorService commandExecutorService;

    private WorkflowExecutionRegistry registry;
    private RunControlService runControl;

    @BeforeEach
    void setUp() {
        registry = new WorkflowExecutionRegistry();
        runControl = new RunControlService(registry, runRepository, broadcaster, activityLogService,
                commandExecutorService);
    }

    /** A run whose execution thread has taken over (promoted from its provisional context). */
    private ExecutionContext startedRun(String runId, String containerId) {
        ExecutionContext context = ExecutionContext.builder().workflowRunId(runId).containerId(containerId).build();
        registry.registerProvisional(runId);
        registry.promote(runId, context);
        return context;
    }

    private static WorkflowRun persistedRun(String runId, RunStatus status) {
        return WorkflowRun.builder().id(runId).status(status).startedAt(Instant.now()).build();
    }

    // ── pause / resume ────────────────────────────────────────────────────────

    @Test
    void pauseAndResumeExecution() {
        runControl.pauseExecution("non-existent-run");
        runControl.resumeExecution("non-existent-run");

        verifyNoInteractions(runRepository, broadcaster);
    }

    @Test
    void pauseAndResumeExecution_registeredRun_togglesThePauseFlag() {
        ExecutionContext context = startedRun("run-1", "c1");

        runControl.pauseExecution("run-1");
        assertThat(context.isPaused()).isTrue();

        runControl.resumeExecution("run-1");
        assertThat(context.isPaused()).isFalse();
        // The execution thread persists PAUSED/RUNNING itself — control only flips the flag.
        verifyNoInteractions(runRepository, broadcaster);
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancelExecution_nonExistentRun_doesNotThrow() {
        runControl.cancelExecution("non-existent-run");
        // No exception expected
    }

    @Test
    void cancelExecution_registeredRun_flagsTheContextWithoutTouchingTheDatabase() {
        ExecutionContext context = startedRun("run-1", "c1");

        runControl.cancelExecution("run-1");

        assertThat(context.shouldStop()).isTrue();
        verifyNoInteractions(runRepository, broadcaster, activityLogService);
    }

    @Test
    void cancelExecution_queuedRun_isNoGhost() {
        registry.registerProvisional("run-1"); // still waiting for a permit or its container

        runControl.cancelExecution("run-1");

        assertThat(registry.cancelRequested("run-1")).isTrue();
        verifyNoInteractions(runRepository, broadcaster, activityLogService);
    }

    @Test
    void cancelExecution_ghostRun_updatesPersistedStatusToCancelled() {
        // Run exists in DB but no active execution context in the registry
        // (simulates a stale RUNNING row after backend crash/restart).
        when(runRepository.findById("run-1")).thenReturn(Optional.of(persistedRun("run-1", RunStatus.RUNNING)));

        runControl.cancelExecution("run-1");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository, atLeastOnce()).save(captor.capture());
        WorkflowRun saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void cancelExecution_ghostRun_stampsTheErrorBroadcastsAndLogs() {
        when(runRepository.findById("run-1")).thenReturn(Optional.of(persistedRun("run-1", RunStatus.RUNNING)));

        runControl.cancelExecution("run-1");

        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runRepository).save(captor.capture());
        WorkflowRun saved = captor.getValue();
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getDurationMs()).isNotNull();
        assertThat(String.valueOf(saved.getError().get("message"))).contains("ghost run");
        verify(broadcaster).broadcastRunStatus("run-1", RunStatus.CANCELLED);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), eq("Ghost run force-cancelled: run-1"),
                eq("WorkflowExecutor"), eq("run-1"), isNull());
    }

    @Test
    void cancelExecution_unregisteredRunThatAlreadyEnded_isLeftAlone() {
        when(runRepository.findById("run-1")).thenReturn(Optional.of(persistedRun("run-1", RunStatus.COMPLETED)));

        runControl.cancelExecution("run-1");

        verify(runRepository, never()).save(any());
        verifyNoInteractions(broadcaster, activityLogService);
    }

    @Test
    void cancelExecution_ghostCancelFails_doesNotPropagate() {
        when(runRepository.findById("run-1")).thenThrow(new IllegalStateException("db down"));

        runControl.cancelExecution("run-1");

        verifyNoInteractions(broadcaster, activityLogService);
    }

    // ── approvals ─────────────────────────────────────────────────────────────

    @Test
    void getPendingApprovalAndResolveApproval_goThroughTheRegistry() {
        ExecutionContext context = startedRun("run-1", "c1");
        ApprovalRequest request = ApprovalRequest.builder()
                .question("Proceed?").options(List.of("Yes", "No")).build();
        var answer = context.requestApproval(request);

        assertThat(runControl.getPendingApproval("run-1")).isSameAs(request);
        runControl.resolveApproval("run-1", "Yes");

        assertThat(answer).isCompletedWithValue("Yes");
    }

    @Test
    void resolveApproval_answerNotOffered_isRefused() {
        ExecutionContext context = startedRun("run-1", "c1");
        var answer = context.requestApproval(ApprovalRequest.builder()
                .question("Satz uebernehmen?").options(List.of("Ja", "Nein")).build());

        assertThatThrownBy(() -> runControl.resolveApproval("run-1", "Vielleicht"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(answer).isNotDone();
    }

    @Test
    void getPendingApproval_unknownRun_returnsNull() {
        assertThat(runControl.getPendingApproval("non-existent-run")).isNull();
    }

    // ── ad-hoc command ────────────────────────────────────────────────────────

    @Test
    void executeAdHocCommand_noActiveRun_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowExecutionException.class,
                () -> runControl.executeAdHocCommand("non-existent", "echo hello")
        );
    }

    @Test
    void executeAdHocCommand_runStillQueued_isRefused() {
        // The provisional context makes the run cancellable, but there is no container and no
        // project cwd yet — an ad-hoc command must still be refused, not run on the host.
        registry.registerProvisional("run-1");

        assertThatThrownBy(() -> runControl.executeAdHocCommand("run-1", "echo hello"))
                .isInstanceOf(WorkflowExecutionException.class);

        verify(commandExecutorService, never()).executeCommand(any(), anyString(), anyString());
    }

    @Test
    void executeAdHocCommand_startedRun_runsInItsContainerAndBroadcastsTheOutput() {
        startedRun("run-1", "docker-container-1");
        when(commandExecutorService.executeCommand("docker-container-1", "claude", "ls -la"))
                .thenReturn(new CliSession.CommandResult("file1.txt", 0));

        String output = runControl.executeAdHocCommand("run-1", "ls -la");

        assertThat(output).isEqualTo("file1.txt");
        verify(broadcaster).broadcastLogMessage("run-1", "command", Map.of("input", "ls -la", "output", "file1.txt"));
    }

    @Test
    void executeAdHocCommand_withoutOutput_returnsEmptyString() {
        startedRun("run-1", "docker-container-1");
        when(commandExecutorService.executeCommand("docker-container-1", "claude", "true"))
                .thenReturn(new CliSession.CommandResult(null, 0));

        assertThat(runControl.executeAdHocCommand("run-1", "true")).isEmpty();
        verify(broadcaster).broadcastLogMessage("run-1", "command", Map.of("input", "true", "output", ""));
    }

    // ── startup cleanup ───────────────────────────────────────────────────────

    @Test
    void cleanupZombieRuns_marksRunningRunsAsInterrupted() {
        WorkflowRun orphan1 = WorkflowRun.builder().id("orphan-1").status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        WorkflowRun orphan2 = WorkflowRun.builder().id("orphan-2").status(RunStatus.RUNNING).startedAt(Instant.now()).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(orphan1, orphan2));
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of());

        runControl.cleanupZombieRuns();

        assertThat(orphan1.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(orphan1.getCompletedAt()).isNotNull();
        assertThat(orphan1.getError()).containsKey("message");
        assertThat(orphan2.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        verify(runRepository, times(2)).save(any(WorkflowRun.class));
    }

    @Test
    void cleanupZombieRuns_pausedRunsToo_andLogsTheCount() {
        WorkflowRun running = persistedRun("orphan-1", RunStatus.RUNNING);
        WorkflowRun paused = persistedRun("orphan-2", RunStatus.PAUSED);
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(running));
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of(paused));

        runControl.cleanupZombieRuns();

        assertThat(paused.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(paused.getDurationMs()).isNotNull();
        verify(activityLogService).warn(LogCategory.EXECUTION,
                "Startup cleanup: marked 2 run(s) as INTERRUPTED", "WorkflowExecutor");
    }

    @Test
    void cleanupZombieRuns_noZombies_doesNothing() {
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(runRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of());

        runControl.cleanupZombieRuns();

        verify(runRepository, never()).save(any(WorkflowRun.class));
        verifyNoInteractions(activityLogService);
    }
}
