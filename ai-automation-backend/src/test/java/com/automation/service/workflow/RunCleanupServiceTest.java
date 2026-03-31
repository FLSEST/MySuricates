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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunCleanupServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private ActivityLogService activityLogService;

    private RunCleanupService service;

    @BeforeEach
    void setUp() {
        service = new RunCleanupService(runRepository, activityLogService);
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
}
