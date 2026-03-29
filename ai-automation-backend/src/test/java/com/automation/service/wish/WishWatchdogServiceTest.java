package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishWatchdogServiceTest {

    @Mock
    private WishConfig wishConfig;

    @Mock
    private WishRepository wishRepository;

    @Mock
    private WishService wishService;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private ChainRunRepository chainRunRepository;

    private WishWatchdogService watchdogService;

    @BeforeEach
    void setUp() {
        watchdogService = new WishWatchdogService(
                wishConfig, wishRepository, wishService,
                workflowRunRepository, chainRunRepository);
    }

    @Nested
    class RecoverOnStartupTests {

        @Test
        void recoverOnStartup_watchdogDisabled_doesNothing() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(false);

            watchdogService.recoverOnStartup();

            verifyNoInteractions(wishRepository);
        }

        @Test
        void recoverOnStartup_noStuckWishes_doesNothing() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of());

            watchdogService.recoverOnStartup();

            verify(wishRepository, never()).save(any());
        }

        @Test
        void recoverOnStartup_stuckDecomposingWish_markedFailed() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-1", WishStatus.DECOMPOSING, null);
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
            assertThat(wish.getError()).contains("Watchdog recovery");
            verify(wishRepository).save(wish);
            verify(wishService).broadcastStatus(eq("w-1"), eq(wish));
        }

        @Test
        void recoverOnStartup_executingWishWithTerminalRun_movesToEvaluating() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-2", WishStatus.EXECUTING, "run-1");
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));
            when(workflowRunRepository.findById("run-1"))
                    .thenReturn(Optional.of(createWorkflowRun(RunStatus.COMPLETED)));

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
            verify(wishRepository).save(wish);
            verify(wishService).fulfillAsync("w-2");
        }

        @Test
        void recoverOnStartup_executingWishWithRunningRun_markedFailed() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-3", WishStatus.EXECUTING, "run-2");
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));
            when(workflowRunRepository.findById("run-2"))
                    .thenReturn(Optional.of(createWorkflowRun(RunStatus.RUNNING)));

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
        }

        @Test
        void recoverOnStartup_executingWishWithNullRunId_markedFailed() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-4", WishStatus.EXECUTING, null);
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
        }

        @Test
        void recoverOnStartup_recoverFails_continuesWithOthers() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish1 = createWish("w-5", WishStatus.DECOMPOSING, null);
            Wish wish2 = createWish("w-6", WishStatus.DECOMPOSING, null);

            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish1, wish2));
            // First save throws, second should still proceed
            when(wishRepository.save(any()))
                    .thenThrow(new RuntimeException("DB error"))
                    .thenAnswer(inv -> inv.getArgument(0));

            watchdogService.recoverOnStartup();

            // Both wishes should have been attempted (broadcastStatus called before save in failure case)
            verify(wishRepository, times(2)).save(any());
        }
    }

    @Nested
    class CheckForStaleWishesTests {

        @Test
        void checkForStaleWishes_watchdogDisabled_doesNothing() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(false);

            watchdogService.checkForStaleWishes();

            verifyNoInteractions(wishRepository);
        }

        @Test
        void checkForStaleWishes_noStaleWishes_doesNothing() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            when(wishConfig.getWatchdogStaleMinutes()).thenReturn(45);
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of());

            watchdogService.checkForStaleWishes();

            verify(wishRepository, never()).save(any());
        }

        @Test
        void checkForStaleWishes_staleWish_recoversAndBroadcasts() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            when(wishConfig.getWatchdogStaleMinutes()).thenReturn(45);
            Wish wish = createWish("w-7", WishStatus.REFINING, null);
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));

            watchdogService.checkForStaleWishes();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
            verify(wishService).broadcastStatus(eq("w-7"), eq(wish));
        }
    }

    @Nested
    class RunTerminalTests {

        @Test
        void executingWish_terminalChainRun_movesToEvaluating() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-8", WishStatus.EXECUTING, "chain-run-1");
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));
            when(workflowRunRepository.findById("chain-run-1")).thenReturn(Optional.empty());
            when(chainRunRepository.findById("chain-run-1"))
                    .thenReturn(Optional.of(createChainRun(RunStatus.FAILED)));

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
            verify(wishService).fulfillAsync("w-8");
        }

        @Test
        void executingWish_runNotFoundAnywhere_treatedAsTerminal() {
            when(wishConfig.isWatchdogEnabled()).thenReturn(true);
            Wish wish = createWish("w-9", WishStatus.EXECUTING, "missing-run");
            when(wishRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                    .thenReturn(List.of(wish));
            when(workflowRunRepository.findById("missing-run")).thenReturn(Optional.empty());
            when(chainRunRepository.findById("missing-run")).thenReturn(Optional.empty());

            watchdogService.recoverOnStartup();

            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
        }
    }

    // --- Helpers ---

    private Wish createWish(String id, WishStatus status, String currentRunId) {
        return Wish.builder()
                .id(id)
                .originalText("Test wish")
                .status(status)
                .currentRunId(currentRunId)
                .updatedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
    }

    private WorkflowRun createWorkflowRun(RunStatus status) {
        return WorkflowRun.builder()
                .id("run-id")
                .status(status)
                .build();
    }

    private ChainRun createChainRun(RunStatus status) {
        return ChainRun.builder()
                .id("chain-run-id")
                .status(status)
                .build();
    }
}
