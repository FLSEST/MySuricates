package com.automation.service.chain;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ChainRun;
import com.automation.entity.ChainRunEntry;
import com.automation.entity.RunStatus;
import com.automation.repository.ChainRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Moved from {@code ChainExecutorTest} plus the edge cases of the startup cleanup (Audit-TODO 050b85d8). */
@ExtendWith(MockitoExtension.class)
class ChainZombieRunCleanerTest {

    @Mock private ChainRunRepository chainRunRepository;
    @Mock private ActivityLogService activityLogService;

    private ChainZombieRunCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new ChainZombieRunCleaner(chainRunRepository, activityLogService);
    }

    @Test
    void cleanupZombieChainRuns_marksNestedEntryRunsInterrupted() {
        ChainRunEntry runningEntry = ChainRunEntry.builder()
                .entryIndex(0).workflowId("wf-1").status(RunStatus.RUNNING)
                .startedAt(Instant.now()).build();
        ChainRunEntry completedEntry = ChainRunEntry.builder()
                .entryIndex(1).workflowId("wf-2").status(RunStatus.COMPLETED)
                .startedAt(Instant.now()).completedAt(Instant.now()).build();
        ChainRun zombie = ChainRun.builder()
                .id("zombie-1").status(RunStatus.RUNNING).startedAt(Instant.now())
                .entryRuns(new ArrayList<>(List.of(runningEntry, completedEntry)))
                .build();

        when(chainRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of(zombie));
        when(chainRunRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of());

        cleaner.cleanupZombieChainRuns();

        ArgumentCaptor<ChainRun> captor = ArgumentCaptor.forClass(ChainRun.class);
        verify(chainRunRepository).save(captor.capture());
        ChainRun saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(saved.getDurationMs()).isNotNull();
        // RUNNING entry reconciled to INTERRUPTED + completedAt
        assertThat(saved.getEntryRuns().get(0).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(saved.getEntryRuns().get(0).getCompletedAt()).isNotNull();
        // Already-COMPLETED entry left untouched
        assertThat(saved.getEntryRuns().get(1).getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION),
                eq("Startup cleanup: marked 1 chain-run(s) as INTERRUPTED"), eq("ChainExecutor"));
    }

    @Test
    void cleanupZombieChainRuns_pausedRunsAreIncluded() {
        ChainRun paused = ChainRun.builder().id("z-2").status(RunStatus.PAUSED).build();
        when(chainRunRepository.findByStatus(RunStatus.RUNNING)).thenReturn(List.of());
        when(chainRunRepository.findByStatus(RunStatus.PAUSED)).thenReturn(List.of(paused));

        cleaner.cleanupZombieChainRuns();

        assertThat(paused.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(paused.getCompletedAt()).isNotNull();
        assertThat(paused.getDurationMs()).isNull();
        verify(chainRunRepository).save(paused);
    }

    @Test
    void cleanupZombieChainRuns_nothingStuck_noSaveNoLog() {
        when(chainRunRepository.findByStatus(any())).thenReturn(List.of());

        cleaner.cleanupZombieChainRuns();

        verify(chainRunRepository, never()).save(any());
        verify(activityLogService, never()).warn(any(), anyString(), anyString());
    }
}
