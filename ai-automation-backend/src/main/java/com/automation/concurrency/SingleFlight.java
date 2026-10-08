package com.automation.concurrency;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
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
 * <p>Failures are shared on purpose: a transient error (Docker unreachable, a probe timing out)
 * would most likely hit every waiter too, and letting each of them retry in turn would multiply
 * the wait. The one exception is a computation that failed because <em>its own</em> thread was
 * interrupted (a cancelled run): that says nothing about the work, so the waiters do not get
 * that failure but try again, and one of them becomes the new leader - as they would have done
 * with a monitor. The interrupted caller still gets its exception and keeps its interrupt flag.
 *
 * <p>The entry for a key is removed as soon as its computation ends, successfully or not, and
 * before the waiters are released: the map only holds keys with a running computation, and a
 * call after a failure starts afresh. Results are not cached. Not reentrant: a computation must
 * not call {@link #run} for its own key.
 */
public final class SingleFlight<K, V> {

    /** Cause chains are followed at most this deep (guards against cyclic causes). */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * Runs {@code computation} for {@code key}, or waits for the one already running for it.
     * Waiting is not interruptible, like waiting for a monitor was not.
     */
    public V run(K key, Supplier<? extends V> computation) {
        while (true) {
            CompletableFuture<V> mine = new CompletableFuture<>();
            CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
            if (running == null) {
                return lead(key, mine, computation);
            }
            try {
                return running.join();
            } catch (CompletionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof LeaderInterrupted) {
                    continue;
                }
                // Hand the waiter the leader's own exception, so callers and the
                // GlobalExceptionHandler see the same type whoever ran the computation.
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

    /** Number of keys whose computation is running right now (for tests and diagnostics). */
    public int inFlight() {
        return inFlight.size();
    }

    private V lead(K key, CompletableFuture<V> mine, Supplier<? extends V> computation) {
        V result;
        try {
            result = computation.get();
        } catch (Throwable failure) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(failedByInterrupt(failure) ? new LeaderInterrupted(failure) : failure);
            throw failure;
        }
        inFlight.remove(key, mine);
        mine.complete(result);
        return result;
    }

    /**
     * Whether the leader's failure comes from an interrupt of its own thread: the flag is still
     * set, or the cause chain holds the exception an interrupted wait or I/O call throws. A
     * {@link SocketTimeoutException} is an {@link InterruptedIOException} too, but a timeout,
     * not an interrupt.
     */
    static boolean failedByInterrupt(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException
                    || (current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Tells waiters that the leader was interrupted and they should try again. */
    private static final class LeaderInterrupted extends RuntimeException {
        LeaderInterrupted(Throwable cause) {
            super("leader interrupted", cause, false, false);
        }
    }
}
