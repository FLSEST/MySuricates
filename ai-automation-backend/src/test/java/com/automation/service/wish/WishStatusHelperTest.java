package com.automation.service.wish;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishStatusHelperTest {

    @Mock private WishRepository wishRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Nested
    class IsTerminalTests {

        @Test
        void completed_isTerminal() {
            assertThat(WishStatusHelper.isTerminal(WishStatus.COMPLETED)).isTrue();
        }

        @Test
        void failed_isTerminal() {
            assertThat(WishStatusHelper.isTerminal(WishStatus.FAILED)).isTrue();
        }

        @Test
        void cancelled_isTerminal() {
            assertThat(WishStatusHelper.isTerminal(WishStatus.CANCELLED)).isTrue();
        }

        @Test
        void nonTerminalStatuses_allReturnFalse() {
            for (WishStatus status : List.of(
                    WishStatus.RECEIVED, WishStatus.DECOMPOSING, WishStatus.EXECUTING,
                    WishStatus.EVALUATING, WishStatus.REFINING,
                    WishStatus.AWAITING_DECOMPOSE_APPROVAL, WishStatus.AWAITING_CLARIFICATION,
                    WishStatus.AWAITING_APPROVAL)) {
                assertThat(WishStatusHelper.isTerminal(status))
                        .as("Expected %s to be non-terminal", status)
                        .isFalse();
            }
        }
    }

    @Nested
    class IsPausedTests {

        @Test
        void awaitingApproval_isPaused() {
            assertThat(WishStatusHelper.isPaused(WishStatus.AWAITING_APPROVAL)).isTrue();
        }

        @Test
        void awaitingClarification_isPaused() {
            assertThat(WishStatusHelper.isPaused(WishStatus.AWAITING_CLARIFICATION)).isTrue();
        }

        @Test
        void awaitingDecomposeApproval_isPaused() {
            assertThat(WishStatusHelper.isPaused(WishStatus.AWAITING_DECOMPOSE_APPROVAL)).isTrue();
        }

        @Test
        void nonPausedStatuses_allReturnFalse() {
            for (WishStatus status : List.of(
                    WishStatus.RECEIVED, WishStatus.DECOMPOSING, WishStatus.EXECUTING,
                    WishStatus.EVALUATING, WishStatus.REFINING,
                    WishStatus.COMPLETED, WishStatus.FAILED, WishStatus.CANCELLED)) {
                assertThat(WishStatusHelper.isPaused(status))
                        .as("Expected %s to be non-paused", status)
                        .isFalse();
            }
        }
    }

    @Nested
    @SuppressWarnings("unchecked")
    class BroadcastStatusTests {

        @Test
        void broadcastsCorrectPayload() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING, 2, 5);

            WishStatusHelper.broadcastStatus(messagingTemplate, "w-1", wish);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/wishes/w-1/status"), (Object) captor.capture());

            Map<String, Object> payload = captor.getValue();
            assertThat(payload).containsEntry("wishId", "w-1");
            assertThat(payload).containsEntry("status", "EXECUTING");
            assertThat(payload).containsEntry("iterationCount", 2);
            assertThat(payload).containsEntry("maxIterations", 5);
        }

        @Test
        void messagingError_doesNotThrow() {
            Wish wish = createWish("w-1", WishStatus.EXECUTING, 1, 5);
            doThrow(new RuntimeException("WebSocket down"))
                    .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

            WishStatusHelper.broadcastStatus(messagingTemplate, "w-1", wish);
            // No exception — silently caught
        }
    }

    @Nested
    class UpdateStatusTests {

        @Test
        void setsStatusSavesAndBroadcasts() {
            Wish wish = createWish("w-1", WishStatus.RECEIVED, 0, 5);
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.DECOMPOSING);

            assertThat(wish.getStatus()).isEqualTo(WishStatus.DECOMPOSING);
            verify(wishRepository).save(wish);
            verify(messagingTemplate).convertAndSend(eq("/topic/wishes/w-1/status"), (Object) any(Map.class));
        }
    }

    @Nested
    class UpdateCurrentIterationEntryTests {

        @Test
        void updatesLastEntryInHistory() {
            Map<String, Object> entry1 = new HashMap<>(Map.of("phase", "decompose"));
            Map<String, Object> entry2 = new HashMap<>(Map.of("phase", "execute"));
            Wish wish = Wish.builder()
                    .id("w-1")
                    .iterationHistory(new ArrayList<>(List.of(entry1, entry2)))
                    .build();

            WishStatusHelper.updateCurrentIterationEntry(wish, "runId", "run-123");

            List<Map<String, Object>> history = wish.getIterationHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(1)).containsEntry("runId", "run-123");
            assertThat(history.get(1)).containsEntry("phase", "execute");
            // First entry unmodified
            assertThat(history.get(0)).doesNotContainKey("runId");
        }

        @Test
        void emptyHistory_doesNothing() {
            Wish wish = Wish.builder()
                    .id("w-1")
                    .iterationHistory(new ArrayList<>())
                    .build();

            WishStatusHelper.updateCurrentIterationEntry(wish, "key", "value");

            assertThat(wish.getIterationHistory()).isEmpty();
        }

        @Test
        void addsNewKeyToExistingEntry() {
            Map<String, Object> entry = new HashMap<>(Map.of("phase", "decompose"));
            Wish wish = Wish.builder()
                    .id("w-1")
                    .iterationHistory(new ArrayList<>(List.of(entry)))
                    .build();

            WishStatusHelper.updateCurrentIterationEntry(wish, "workflowIds", List.of("wf-1"));

            assertThat(wish.getIterationHistory().get(0)).containsKey("phase");
            assertThat(wish.getIterationHistory().get(0)).containsKey("workflowIds");
        }
    }

    // --- Helpers ---

    private Wish createWish(String id, WishStatus status, int iterationCount, int maxIterations) {
        return Wish.builder()
                .id(id)
                .originalText("Test wish")
                .status(status)
                .iterationCount(iterationCount)
                .maxIterations(maxIterations)
                .metadata(new HashMap<>())
                .generatedWorkflowIds(new ArrayList<>())
                .iterationHistory(new ArrayList<>())
                .build();
    }
}
