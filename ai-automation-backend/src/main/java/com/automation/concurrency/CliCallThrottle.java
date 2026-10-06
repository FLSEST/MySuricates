package com.automation.concurrency;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A two-tier limit for AI CLI processes: at most {@link #maxConcurrent()} run at once, and background
 * calls (workflows, suggestions) may use all but {@link #reservedForInteractive()} of them, so an
 * interactive call such as the assistant chat always finds a slot. Background calls take a background
 * permit first and then one of the overall limit; interactive calls take only the overall one.
 * Optionally consecutive calls keep a minimum distance ({@code interCallDelayMs}, read on every call
 * so a runtime setting takes effect at once).
 *
 * <p>Both limits can change at runtime; a decrease lets running calls finish (see
 * {@link DynamicSemaphore}). The background limit is never below one.
 */
public class CliCallThrottle {

    private final String name;
    private final DynamicSemaphore all;
    private final DynamicSemaphore background;
    private final LongSupplier interCallDelayMs;
    private final AtomicLong lastCallTimestamp = new AtomicLong(0);
    private volatile int reservedForInteractive;

    public CliCallThrottle(String name, int maxConcurrent, int reservedForInteractive, LongSupplier interCallDelayMs) {
        this.name = name;
        this.all = new DynamicSemaphore(maxConcurrent);
        this.reservedForInteractive = reservedForInteractive;
        this.background = new DynamicSemaphore(backgroundLimit(maxConcurrent, reservedForInteractive));
        this.interCallDelayMs = interCallDelayMs;
    }

    /**
     * Waits for a slot, then for the distance to the previous call. On interruption every permit taken
     * so far is given back.
     */
    public void acquire(boolean interactive) throws InterruptedException {
        if (!interactive) {
            background.acquire();
        }
        try {
            all.acquire();
        } catch (InterruptedException e) {
            if (!interactive) background.release();
            throw e;
        }
        try {
            keepDistance();
        } catch (InterruptedException e) {
            release(interactive);
            throw e;
        }
    }

    /** Gives back the permits of one {@link #acquire} with the same {@code interactive}. */
    public void release(boolean interactive) {
        all.release();
        if (!interactive) {
            background.release();
        }
    }

    public synchronized void setMaxConcurrent(int maxConcurrent) {
        all.setMaxPermits(maxConcurrent);
        background.setMaxPermits(backgroundLimit(maxConcurrent, reservedForInteractive));
    }

    public synchronized void setReservedForInteractive(int reserved) {
        this.reservedForInteractive = reserved;
        background.setMaxPermits(backgroundLimit(all.getMaxPermits(), reserved));
    }

    public String name() {
        return name;
    }

    public int maxConcurrent() {
        return all.getMaxPermits();
    }

    public int reservedForInteractive() {
        return reservedForInteractive;
    }

    /** How many background calls may run at once: the limit minus the reserve, at least one. */
    public int backgroundLimit() {
        return background.getMaxPermits();
    }

    private static int backgroundLimit(int maxConcurrent, int reserved) {
        return Math.max(1, maxConcurrent - reserved);
    }

    private void keepDistance() throws InterruptedException {
        long delayMs = interCallDelayMs.getAsLong();
        if (delayMs > 0) {
            long remaining = delayMs - (System.currentTimeMillis() - lastCallTimestamp.get());
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
        }
        lastCallTimestamp.set(System.currentTimeMillis());
    }
}
