package com.automation.testsupport;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Helpers for tests that force an interleaving between threads. {@link #awaitParked} tells a
 * thread that waits on a {@code java.util.concurrent} primitive (state {@code WAITING}) apart
 * from one that waits on a monitor ({@code BLOCKED}): code that runs on virtual threads must
 * use the former, because a monitor pins the carrier thread on Java 21.
 */
public final class ParkedThreads {

    private ParkedThreads() {
    }

    /** Starts a daemon platform thread, so a hanging test does not keep the JVM alive. */
    public static Thread start(Runnable action) {
        Thread thread = new Thread(action);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Polls (no Awaitility dependency) until the thread is parked without a timeout; fails with
     * the last observed state otherwise.
     */
    public static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread never parked; state=" + thread.getState());
    }

    /** Waits for a latch inside a stubbed call; fails instead of hanging when nobody opens it. */
    public static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a latch", e);
        }
    }

    /** Joins the thread and fails if it is still running after 5 s. */
    public static void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        if (thread.isAlive()) {
            throw new AssertionError("thread did not finish within 5s; state=" + thread.getState());
        }
    }
}
