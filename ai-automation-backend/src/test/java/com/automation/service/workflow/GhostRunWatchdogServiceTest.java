package com.automation.service.workflow;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GhostRunWatchdogServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private DynamicSemaphore executionSemaphore;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private ActivityLogService activityLogService;

    private GhostRunWatchdogService service;

    @BeforeEach
    void setUp() {
        service = new GhostRunWatchdogService(runRepository, workflowExecutor,
                executionSemaphore, broadcaster, activityLogService);
    }

    @Test
    void detectGhostRuns_noRunningRuns_reconcileOnly() {
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(workflowExecutor.getActiveRunCount()).thenReturn(0);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(5);

        service.detectGhostRuns();

        verify(runRepository, never()).save(any());
    }

    @Test
    void detectGhostRuns_activeExecution_skipped() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                .startedAt(Instant.now().minus(20, ChronoUnit.MINUTES)).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(workflowExecutor.isRunActive("r1")).thenReturn(true);
        when(workflowExecutor.getActiveRunCount()).thenReturn(1);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(4);

        service.detectGhostRuns();

        verify(runRepository, never()).save(any());
    }

    @Test
    void detectGhostRuns_recentlyStarted_skipped() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                .startedAt(Instant.now().minus(2, ChronoUnit.MINUTES)).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(workflowExecutor.isRunActive("r1")).thenReturn(false);
        when(workflowExecutor.getActiveRunCount()).thenReturn(0);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(5);

        service.detectGhostRuns();

        verify(runRepository, never()).save(any());
    }

    @Test
    void detectGhostRuns_staleRun_markedFailed() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                .startedAt(Instant.now().minus(30, ChronoUnit.MINUTES)).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(workflowExecutor.isRunActive("r1")).thenReturn(false);
        when(workflowExecutor.getActiveRunCount()).thenReturn(0);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(5);

        service.detectGhostRuns();

        verify(runRepository).save(argThat(r -> r.getStatus() == RunStatus.FAILED));
        verify(broadcaster).broadcastRunStatus("r1", RunStatus.FAILED);
        verify(activityLogService).warn(any(), contains("ghost run"), anyString());
    }

    @Test
    void detectGhostRuns_broadcastsStatusChange() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                .startedAt(Instant.now().minus(15, ChronoUnit.MINUTES)).build();
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
        when(workflowExecutor.isRunActive("r1")).thenReturn(false);
        when(workflowExecutor.getActiveRunCount()).thenReturn(0);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(5);

        service.detectGhostRuns();

        verify(broadcaster).broadcastRunStatus("r1", RunStatus.FAILED);
    }

    @Test
    void reconcilePermits_leakedPermits_released() {
        when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(workflowExecutor.getActiveRunCount()).thenReturn(1);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(2); // expected 4, got 2 → 2 leaked

        service.detectGhostRuns();

        verify(executionSemaphore).release(2);
        verify(activityLogService).warn(any(), contains("leaked"), anyString());
    }
}
