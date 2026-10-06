package com.automation.executor;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The waiting state of a run at an approval: the open promise, the question currently
 * being waited on, and the answers the run has remembered.
 *
 * <p>Pure move out of {@link ExecutionContext} (TODO 7473fdcb) - the same mechanics, just in one
 * place instead of spread over three fields and five methods. Following the example of
 * {@link ExecutionContextTaint}: one state, one class.
 */
class ApprovalGate {

    private final AtomicReference<CompletableFuture<String>> pending = new AtomicReference<>();
    private final AtomicReference<ApprovalRequest> currentRequest = new AtomicReference<>();
    private final Map<String, String> memory = new ConcurrentHashMap<>();
    /**
     * Who displays the waiting step (TODO 4eea7a87): {@code true} as soon as a question is open,
     * {@code false} when the waiting ends - both on the run's thread, never on the one that answers.
     */
    private volatile Consumer<Boolean> waitListener;

    void onWait(Consumer<Boolean> listener) {
        this.waitListener = listener;
    }

    AtomicReference<ApprovalRequest> currentRequest() {
        return currentRequest;
    }

    CompletableFuture<String> request(ApprovalRequest request) {
        CompletableFuture<String> future = new CompletableFuture<>();
        currentRequest.set(request);
        pending.set(future);
        notifyWaiting(true);
        return future;
    }

    /** Waits for the answer; afterwards the step shows again that it is running - also on timeout or cancel. */
    String await(CompletableFuture<String> future, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeout, unit);
        } finally {
            notifyWaiting(false);
        }
    }

    private void notifyWaiting(boolean waiting) {
        Consumer<Boolean> listener = waitListener;
        if (listener != null) listener.accept(waiting);
    }

    void resolve(String answer) {
        CompletableFuture<String> future = pending.getAndSet(null);
        currentRequest.set(null);
        if (future != null) {
            future.complete(answer);
        }
    }

    /** Cancels an open promise - the waiting block runs into the CancellationException. */
    void cancel() {
        CompletableFuture<String> future = pending.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new CancellationException("Execution cancelled"));
        }
        currentRequest.set(null);
    }

    void remember(String question, String answer) {
        memory.put(question, answer);
    }

    String remembered(String question) {
        return memory.get(question);
    }
}
