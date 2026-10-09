package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishFulfillmentLoopTest {

    @Mock private WishRepository wishRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ExecutorService virtualThreadExecutor;
    @Mock private WishDecomposeService decomposeService;
    @Mock private WishExecutionService executionService;
    @Mock private WishEvaluationService evaluationService;

    private WishConfig wishConfig;
    private WishFulfillmentLoop loop;
    private Wish wish;

    @BeforeEach
    void setUp() {
        wishConfig = new WishConfig();
        loop = new WishFulfillmentLoop(wishConfig, wishRepository, messagingTemplate, virtualThreadExecutor,
                decomposeService, executionService, evaluationService);
        wish = Wish.builder().id("w-1").originalText("x").status(WishStatus.RECEIVED).metadata(new HashMap<>()).build();
        lenient().when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));
        lenient().when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Runs the submitted task inline, like ChainExecutorTest's synchronous executor. */
    private void runInline() {
        when(virtualThreadExecutor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
    }

    private void onDecompose(WishStatus next) throws Exception {
        doAnswer(inv -> { wish.setStatus(next); return null; }).when(decomposeService).decompose(any());
    }

    private void onExecute(WishStatus next) throws Exception {
        doAnswer(inv -> { wish.setStatus(next); return null; }).when(executionService).execute(any(), any());
    }

    private void onEvaluate(WishStatus next) throws Exception {
        doAnswer(inv -> { wish.setStatus(next); return null; }).when(evaluationService).evaluate(any());
    }

    @Test
    void fulfillAsync_submitsToVirtualThreadExecutor() {
        loop.fulfillAsync("w-1");

        verify(virtualThreadExecutor).submit(any(Runnable.class));
    }

    @Test
    void loop_received_decomposes_andStopsWhenPaused() throws Exception {
        runInline();
        onDecompose(WishStatus.AWAITING_DECOMPOSE_APPROVAL);

        loop.fulfillAsync("w-1");

        verify(decomposeService).decompose(wish);
        verify(executionService, never()).execute(any(), any());
        verify(evaluationService, never()).evaluate(any());
        assertThat(wish.getStatus()).isEqualTo(WishStatus.AWAITING_DECOMPOSE_APPROVAL);
    }

    @Test
    void loop_runsAllThreePhases_andLoopsOnRefining() throws Exception {
        runInline();
        doAnswer(inv -> { wish.setStatus(WishStatus.EXECUTING); return null; })
                .doAnswer(inv -> { wish.setStatus(WishStatus.COMPLETED); return null; })
                .when(decomposeService).decompose(any());
        onExecute(WishStatus.EVALUATING);
        onEvaluate(WishStatus.REFINING);

        loop.fulfillAsync("w-1");

        verify(decomposeService, times(2)).decompose(wish);
        verify(executionService).execute(any(), any());
        verify(evaluationService).evaluate(wish);
        assertThat(wish.getStatus()).isEqualTo(WishStatus.COMPLETED);
    }

    @Test
    void loop_executing_skipsDecompose() throws Exception {
        runInline();
        wish.setStatus(WishStatus.EXECUTING);
        onExecute(WishStatus.EVALUATING);
        onEvaluate(WishStatus.AWAITING_APPROVAL);

        loop.fulfillAsync("w-1");

        verify(decomposeService, never()).decompose(any());
        verify(executionService).execute(any(), any());
        verify(evaluationService).evaluate(wish);
    }

    @Test
    void loop_terminalAtStart_doesNothing() throws Exception {
        runInline();
        wish.setStatus(WishStatus.COMPLETED);

        loop.fulfillAsync("w-1");

        verify(decomposeService, never()).decompose(any());
        verify(wishRepository, never()).save(any());
    }

    @Test
    void loop_cancelledBeforeStart_marksCancelled() throws Exception {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        when(virtualThreadExecutor.submit(task.capture())).thenReturn(null);
        wish.setStatus(WishStatus.EXECUTING);

        loop.fulfillAsync("w-1");
        loop.cancel("w-1");
        task.getValue().run();

        assertThat(wish.getStatus()).isEqualTo(WishStatus.CANCELLED);
        verify(wishRepository, atLeastOnce()).save(wish);
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void loop_cancelSupplier_isPassedToExecution() throws Exception {
        runInline();
        wish.setStatus(WishStatus.EXECUTING);
        doAnswer(inv -> {
            java.util.function.Supplier<Boolean> cancelled = inv.getArgument(1);
            assertThat(cancelled.get()).isFalse();
            wish.setStatus(WishStatus.COMPLETED);
            return null;
        }).when(executionService).execute(any(), any());

        loop.fulfillAsync("w-1");

        verify(executionService).execute(any(), any());
    }

    @Test
    void loop_deadlineExceeded_failsWithTimeoutError() throws Exception {
        runInline();
        wishConfig.setTotalTimeoutMinutes(-1);

        loop.fulfillAsync("w-1");

        assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
        assertThat(wish.getError()).isEqualTo("Total timeout exceeded (-1 minutes)");
        verify(decomposeService, never()).decompose(any());
    }

    @Test
    void loop_exception_marksFailedWithMessage() throws Exception {
        runInline();
        doAnswer(inv -> { throw new IllegalStateException("decompose exploded"); }).when(decomposeService).decompose(any());

        loop.fulfillAsync("w-1");

        assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
        assertThat(wish.getError()).isEqualTo("decompose exploded");
        verify(wishRepository, atLeastOnce()).save(wish);
    }

    @Test
    void loop_statusNoPhaseHandles_stopsInsteadOfSpinning() throws Exception {
        // DECOMPOSING at the top of the loop means a second loop for the same wish (fulfillAsync has no
        // run lock): the first one is still decomposing. Count the reads so a spinning loop fails fast.
        runInline();
        wish.setStatus(WishStatus.DECOMPOSING);
        AtomicInteger reads = new AtomicInteger();
        when(wishRepository.findById("w-1")).thenAnswer(inv -> {
            if (reads.incrementAndGet() > 50) throw new IllegalStateException("loop keeps spinning");
            return Optional.of(wish);
        });

        loop.fulfillAsync("w-1");

        assertThat(reads.get()).isLessThanOrEqualTo(5);
        assertThat(wish.getStatus()).isEqualTo(WishStatus.DECOMPOSING);
        verify(decomposeService, never()).decompose(any());
        verify(executionService, never()).execute(any(), any());
        verify(evaluationService, never()).evaluate(any());
        verify(wishRepository, never()).save(any());
    }

    @Test
    void finishingLoop_keepsTheCancelFlagOfALaterLoop() throws Exception {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        when(virtualThreadExecutor.submit(tasks.capture())).thenReturn(null);
        // Ends the second loop if the cancel flag got lost instead of letting it execute forever
        lenient().doAnswer(inv -> { wish.setStatus(WishStatus.COMPLETED); return null; })
                .when(executionService).execute(any(), any());
        wish.setStatus(WishStatus.COMPLETED);

        loop.fulfillAsync("w-1");
        loop.fulfillAsync("w-1");
        tasks.getAllValues().get(0).run(); // the first loop ends at once (terminal)
        loop.cancel("w-1");
        wish.setStatus(WishStatus.EXECUTING);
        tasks.getAllValues().get(1).run();

        assertThat(wish.getStatus()).isEqualTo(WishStatus.CANCELLED);
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void stoppedSecondLoop_leavesTheFirstLoopCancellable() throws Exception {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        when(virtualThreadExecutor.submit(tasks.capture())).thenReturn(null);
        lenient().doAnswer(inv -> { wish.setStatus(WishStatus.COMPLETED); return null; })
                .when(executionService).execute(any(), any());
        wish.setStatus(WishStatus.DECOMPOSING);

        loop.fulfillAsync("w-1"); // first loop, still decomposing
        loop.fulfillAsync("w-1"); // second loop meets DECOMPOSING and stops
        tasks.getAllValues().get(1).run();
        loop.cancel("w-1");
        // The first loop's phase overwrites the CANCELLED that cancel() wrote, as execute() does
        wish.setStatus(WishStatus.EXECUTING);
        tasks.getAllValues().get(0).run();

        assertThat(wish.getStatus()).isEqualTo(WishStatus.CANCELLED);
        verify(executionService, never()).execute(any(), any());
    }

    @Test
    void cancel_activeWish_setsCancelledStatus() {
        wish.setStatus(WishStatus.EXECUTING);

        loop.cancel("w-1");

        assertThat(wish.getStatus()).isEqualTo(WishStatus.CANCELLED);
        verify(wishRepository).save(wish);
    }

    @Test
    void cancel_alreadyTerminal_doesNotSave() {
        wish.setStatus(WishStatus.COMPLETED);

        loop.cancel("w-1");

        verify(wishRepository, never()).save(any());
    }

    @Test
    void cancel_unknownWish_isNoOp() {
        when(wishRepository.findById("nope")).thenReturn(Optional.empty());

        loop.cancel("nope");

        verify(wishRepository, never()).save(any());
    }
}
