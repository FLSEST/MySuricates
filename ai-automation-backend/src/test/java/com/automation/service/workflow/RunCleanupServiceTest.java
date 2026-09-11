package com.automation.service.workflow;

import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunCleanupServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ActivityLogService activityLogService;
    @Mock private RunDeletionService runDeletionService;
    private final com.automation.config.RunRetentionConfig retentionConfig = new com.automation.config.RunRetentionConfig();

    private RunCleanupService service;

    @BeforeEach
    void setUp() {
        service = new RunCleanupService(runRepository, activityLogService, runDeletionService, retentionConfig);
        ReflectionTestUtils.setField(service, "interruptedRetentionDays", 30);
    }

    @Test
    void cleanupInterruptedRuns_emptyRunsOlderThan24h_deleted() {
        when(runRepository.findInterruptedRunIdsWithNoCompletedSteps(any(Instant.class)))
                .thenReturn(List.of("run-1", "run-2"));
        when(runRepository.findInterruptedRunIdsOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        service.cleanupInterruptedRuns();

        verify(runRepository).deleteAllById(List.of("run-1", "run-2"));
    }

    @Test
    void cleanupInterruptedRuns_oldRunsWithSteps_deletedAfterRetention() {
        when(runRepository.findInterruptedRunIdsWithNoCompletedSteps(any(Instant.class)))
                .thenReturn(List.of());
        when(runRepository.findInterruptedRunIdsOlderThan(any(Instant.class)))
                .thenReturn(List.of("old-run-1"));

        service.cleanupInterruptedRuns();

        verify(runRepository).deleteAllById(List.of("old-run-1"));
    }

    @Test
    void cleanupInterruptedRuns_noStaleRuns_noAction() {
        when(runRepository.findInterruptedRunIdsWithNoCompletedSteps(any(Instant.class)))
                .thenReturn(List.of());
        when(runRepository.findInterruptedRunIdsOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        service.cleanupInterruptedRuns();

        verify(runRepository, never()).deleteAllById(any());
        verify(activityLogService, never()).info(any(), anyString(), anyString());
    }

    @Test
    void cleanupInterruptedRuns_bothTypes_logsTotal() {
        when(runRepository.findInterruptedRunIdsWithNoCompletedSteps(any(Instant.class)))
                .thenReturn(List.of("empty-1"));
        when(runRepository.findInterruptedRunIdsOlderThan(any(Instant.class)))
                .thenReturn(List.of("old-1", "old-2"));

        service.cleanupInterruptedRuns();

        verify(activityLogService).info(any(), contains("3"), eq("RunCleanupService"));
    }

    @Test
    void cleanupInterruptedRuns_deletionCount_loggedToActivityLog() {
        when(runRepository.findInterruptedRunIdsWithNoCompletedSteps(any(Instant.class)))
                .thenReturn(List.of("run-1"));
        when(runRepository.findInterruptedRunIdsOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        service.cleanupInterruptedRuns();

        verify(activityLogService).info(any(), contains("1"), eq("RunCleanupService"));
    }

    // ── cleanupTerminalRuns (run retention, TODO 24764423) ─────────────────────────

    @Test
    void cleanupTerminalRuns_disabled_doesNothing() {
        retentionConfig.setRetentionDays(0);

        service.cleanupTerminalRuns();

        verifyNoInteractions(runDeletionService);
        verify(runRepository, never()).findTerminalRunIdsOlderThan(any(), any(Instant.class), any());
    }

    @Test
    void cleanupTerminalRuns_deletesEachCandidateThroughDeletionService_boundedByBatchSize() {
        retentionConfig.setRetentionDays(90);
        retentionConfig.setMaxPerNight(50);
        when(runRepository.findTerminalRunIdsOlderThan(any(), any(Instant.class), any()))
                .thenReturn(List.of("r-1", "r-2"));

        service.cleanupTerminalRuns();

        org.mockito.ArgumentCaptor<Instant> cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(runRepository).findTerminalRunIdsOlderThan(eq(RunCleanupService.TERMINAL_STATUSES), cutoff.capture(), page.capture());
        assertThat(cutoff.getValue()).isBefore(Instant.now().minus(89, java.time.temporal.ChronoUnit.DAYS));
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        verify(runDeletionService).delete("r-1");
        verify(runDeletionService).delete("r-2");
        verify(runRepository, never()).deleteAllById(any());
        verify(activityLogService).info(any(), contains("2 abgeschlossene Run(s)"), eq("RunCleanupService"));
    }

    @Test
    void cleanupTerminalRuns_refusedRun_isSkippedAndOthersStillDeleted() {
        retentionConfig.setRetentionDays(30);
        when(runRepository.findTerminalRunIdsOlderThan(any(), any(Instant.class), any()))
                .thenReturn(List.of("audit-run", "r-2", "boom"));
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            if ("audit-run".equals(id)) throw new IllegalStateException("referenced by an audit report");
            if ("boom".equals(id)) throw new RuntimeException("db");
            return null;
        }).when(runDeletionService).delete(anyString());

        service.cleanupTerminalRuns();

        verify(runDeletionService).delete("r-2");
        verify(activityLogService).info(any(), contains("1 abgeschlossene Run(s)"), eq("RunCleanupService"));
        verify(activityLogService).info(any(), contains("1 übersprungen, 1 fehlgeschlagen"), eq("RunCleanupService"));
    }

    @Test
    void cleanupTerminalRuns_noCandidates_staysSilent() {
        retentionConfig.setRetentionDays(90);
        when(runRepository.findTerminalRunIdsOlderThan(any(), any(Instant.class), any())).thenReturn(List.of());

        service.cleanupTerminalRuns();

        verifyNoInteractions(runDeletionService);
        verify(activityLogService, never()).info(any(), any(), any());
    }

}
