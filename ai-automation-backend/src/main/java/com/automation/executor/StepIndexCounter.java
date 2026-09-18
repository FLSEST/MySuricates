package com.automation.executor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out the step indices of one run. The block loop pre-allocates an index for the RUNNING
 * placeholder it shows immediately; the executor then picks up that same index. Wrapper blocks
 * (foreach, while, retry, …) draw one further index per item or iteration, which is why a block's
 * index runs ahead of its position in the block list — see {@code ResumePlan}.
 */
public class StepIndexCounter {

    private final AtomicInteger counter = new AtomicInteger(0);
    private final AtomicInteger preAllocated = new AtomicInteger(-1);

    /** Reserves the next index; the following {@link #next()} returns this same value. */
    public int preAllocate() {
        int index = counter.getAndIncrement();
        preAllocated.set(index);
        return index;
    }

    public int next() {
        int reserved = preAllocated.getAndSet(-1);
        return reserved >= 0 ? reserved : counter.getAndIncrement();
    }

    /**
     * Resume: numbers this segment's steps from {@code firstIndex} on, so they neither collide with
     * nor sort before the steps the earlier segment persisted.
     */
    public void startAt(int firstIndex) {
        if (firstIndex > 0) {
            counter.set(firstIndex);
        }
    }
}
