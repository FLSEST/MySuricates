package com.automation.concurrency;

import java.util.concurrent.Semaphore;

/**
 * A Semaphore whose permit count can be changed at runtime.
 * Increasing permits takes effect immediately; decreasing lets running
 * operations finish naturally (new acquire() calls block until enough
 * permits are released).
 */
public class DynamicSemaphore extends Semaphore {

    private volatile int maxPermits;

    public DynamicSemaphore(int permits) {
        super(permits);
        this.maxPermits = permits;
    }

    /**
     * Adjusts the maximum number of permits. Thread-safe.
     * - Increase: additional permits are released immediately.
     * - Decrease: permits are reduced; if all are currently acquired,
     *   availablePermits() goes negative and new acquire() calls block
     *   until enough release() calls bring it back up.
     */
    public synchronized void setMaxPermits(int newMax) {
        if (newMax < 1) {
            throw new IllegalArgumentException("maxPermits must be >= 1, got " + newMax);
        }
        int delta = newMax - this.maxPermits;
        this.maxPermits = newMax;
        if (delta > 0) {
            release(delta);
        } else if (delta < 0) {
            reducePermits(-delta);
        }
    }

    public int getMaxPermits() {
        return maxPermits;
    }
}
