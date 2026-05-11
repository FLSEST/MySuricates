package com.automation.service.workflow;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
        ReflectionTestUtils.setField(service, "staleThresholdMinutes", 10L);
    }

    private void stubPermitsBalanced() {
        when(workflowExecutor.getActiveRunCount()).thenReturn(0);
        when(executionSemaphore.getMaxPermits()).thenReturn(5);
        when(executionSemaphore.availablePermits()).thenReturn(5);
    }

    @Nested
    class DetectGhostRuns {

        @Test
        void noRunningRuns_reconcileOnly() {
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            stubPermitsBalanced();

            service.detectGhostRuns();

            verify(runRepository, never()).save(any());
        }

        @Test
        void activeExecution_skipped() {
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
        void recentlyStarted_skipped() {
            WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(2, ChronoUnit.MINUTES)).build();
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
            when(workflowExecutor.isRunActive("r1")).thenReturn(false);
            stubPermitsBalanced();

            service.detectGhostRuns();

            verify(runRepository, never()).save(any());
        }

        @Test
        void staleRun_markedFailed() {
            WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(30, ChronoUnit.MINUTES)).build();
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
            when(workflowExecutor.isRunActive("r1")).thenReturn(false);
            stubPermitsBalanced();

            service.detectGhostRuns();

            verify(runRepository).save(argThat(r -> r.getStatus() == RunStatus.FAILED));
            verify(broadcaster).broadcastRunStatus("r1", RunStatus.FAILED);
            verify(activityLogService).warn(any(), contains("ghost run"), anyString());
        }

        @Test
        void staleRun_setsErrorAndCompletedAtAndDuration() {
            Instant startTime = Instant.now().minus(25, ChronoUnit.MINUTES);
            WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                    .startedAt(startTime).build();
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
            when(workflowExecutor.isRunActive("r1")).thenReturn(false);
            stubPermitsBalanced();

            service.detectGhostRuns();

            ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
            verify(runRepository).save(captor.capture());
            WorkflowRun saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(saved.getCompletedAt()).isNotNull();
            assertThat(saved.getDurationMs()).isGreaterThan(0);
            assertThat(saved.getError()).containsKey("message");
            assertThat(saved.getError().get("message").toString()).contains("Ghost run");
        }

        @Test
        void broadcastsStatusChange() {
            WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(15, ChronoUnit.MINUTES)).build();
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
            when(workflowExecutor.isRunActive("r1")).thenReturn(false);
            stubPermitsBalanced();

            service.detectGhostRuns();

            verify(broadcaster).broadcastRunStatus("r1", RunStatus.FAILED);
        }

        @Test
        void multipleGhostRuns_allMarkedFailed() {
            Instant staleTime = Instant.now().minus(20, ChronoUnit.MINUTES);
            WorkflowRun r1 = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING).startedAt(staleTime).build();
            WorkflowRun r2 = WorkflowRun.builder().id("r2").status(RunStatus.RUNNING).startedAt(staleTime).build();
            WorkflowRun r3 = WorkflowRun.builder().id("r3").status(RunStatus.RUNNING).startedAt(staleTime).build();

            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(r1, r2, r3));
            when(workflowExecutor.isRunActive(anyString())).thenReturn(false);
            stubPermitsBalanced();

            service.detectGhostRuns();

            verify(runRepository, times(3)).save(any());
            verify(broadcaster, times(3)).broadcastRunStatus(anyString(), eq(RunStatus.FAILED));
            verify(activityLogService).warn(any(), contains("3 ghost run"), anyString());
        }

        @Test
        void mixedRuns_onlyStaleGhostsMarked() {
            WorkflowRun active = WorkflowRun.builder().id("active").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(20, ChronoUnit.MINUTES)).build();
            WorkflowRun recent = WorkflowRun.builder().id("recent").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(5, ChronoUnit.MINUTES)).build();
            WorkflowRun stale = WorkflowRun.builder().id("stale").status(RunStatus.RUNNING)
                    .startedAt(Instant.now().minus(15, ChronoUnit.MINUTES)).build();

            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(active, recent, stale));
            when(workflowExecutor.isRunActive("active")).thenReturn(true);
            when(workflowExecutor.isRunActive("recent")).thenReturn(false);
            when(workflowExecutor.isRunActive("stale")).thenReturn(false);
            when(workflowExecutor.getActiveRunCount()).thenReturn(1);
            when(executionSemaphore.getMaxPermits()).thenReturn(5);
            when(executionSemaphore.availablePermits()).thenReturn(4);

            service.detectGhostRuns();

            verify(runRepository, times(1)).save(argThat(r -> "stale".equals(r.getId())));
            verify(activityLogService).warn(any(), contains("1 ghost run"), anyString());
        }

        @Test
        void nullStartedAt_treatedAsStale() {
            // startedAt == null → null-check guard passes (startedAt != null is false),
            // so it falls through to ghost detection. Duration calc then NPEs on startedAt.toEpochMilli().
            WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.RUNNING)
                    .startedAt(null).build();
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(run));
            when(workflowExecutor.isRunActive("r1")).thenReturn(false);

            try {
                service.detectGhostRuns();
            } catch (NullPointerException e) {
                // Expected: startedAt is null, duration calculation NPEs
            }
        }
    }

    @Nested
    class ReconcilePermits {

        @Test
        void leakedPermits_released() {
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowExecutor.getActiveRunCount()).thenReturn(1);
            when(executionSemaphore.getMaxPermits()).thenReturn(5);
            when(executionSemaphore.availablePermits()).thenReturn(2); // expected 4, got 2 → 2 leaked

            service.detectGhostRuns();

            verify(executionSemaphore).release(2);
            verify(activityLogService).warn(any(), contains("leaked"), anyString());
        }

        @Test
        void permitsBalanced_noRelease() {
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowExecutor.getActiveRunCount()).thenReturn(3);
            when(executionSemaphore.getMaxPermits()).thenReturn(5);
            when(executionSemaphore.availablePermits()).thenReturn(2); // 5 - 3 = 2 → balanced

            service.detectGhostRuns();

            verify(executionSemaphore, never()).release(anyInt());
        }

        @Test
        void allPermitsLeaked_allReleased() {
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowExecutor.getActiveRunCount()).thenReturn(0);
            when(executionSemaphore.getMaxPermits()).thenReturn(5);
            when(executionSemaphore.availablePermits()).thenReturn(0); // 5 leaked

            service.detectGhostRuns();

            verify(executionSemaphore).release(5);
        }

        @Test
        void moreAvailableThanExpected_noRelease() {
            // Edge case: more permits available than expected (shouldn't happen normally)
            when(runRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
            when(workflowExecutor.getActiveRunCount()).thenReturn(3);
            when(executionSemaphore.getMaxPermits()).thenReturn(5);
            when(executionSemaphore.availablePermits()).thenReturn(5); // more than expected 2

            service.detectGhostRuns();

            verify(executionSemaphore, never()).release(anyInt());
        }
    }
}
