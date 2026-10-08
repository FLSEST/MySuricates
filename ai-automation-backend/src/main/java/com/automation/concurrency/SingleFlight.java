package com.automation.concurrency;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs at most one computation per key at a time. A caller that arrives while the computation
 * for its key is running does not start its own: it waits for the running one and gets its
 * result, or the very exception it failed with.
 *
 * <p>This replaces {@code synchronized} around blocking work (a process, a Docker call): on
 * Java 21 a virtual thread that holds or waits for a monitor pins its carrier thread, so a
 * handful of runs or web requests waiting for the same slow call could starve the
 * virtual-thread scheduler. Waiting here goes through {@link CompletableFuture#join()}, which
 * parks without pinning. The computation itself runs on the first caller's thread.
 *
 * <p>The entry for a key is removed as soon as its computation ends, successfully or not, and
 * before the waiters are released: the map only holds keys with a running computation, and a
 * call after a failure starts afresh. Results are not cached. Not reentrant: a computation must
 * not call {@link #run} for its own key.
 */
public final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs {@code computation} for {@code key}, or waits for the one already running for it.
     * Waiting is not interruptible, like waiting for a monitor was not.
     */
    public V run(K key, Supplier<? extends V> computation) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        V result;
        try {
            result = computation.get();
        } catch (Throwable failure) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(failure);
            throw failure;
        }
        inFlight.remove(key, mine);
        mine.complete(result);
        return result;
    }

    /** Number of keys whose computation is running right now (for tests and diagnostics). */
    public int inFlight() {
        return inFlight.size();
    }

    private static <V> V await(CompletableFuture<V> running) {
        try {
            return running.join();
        } catch (CompletionException wrapped) {
            // Hand the waiter the leader's own exception, so callers and the
            // GlobalExceptionHandler see the same type whoever ran the computation.
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }
}
