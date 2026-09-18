package com.automation.metrics;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Counts the SQL statements one thread issues while a measurement is active.
 *
 * <p>Fed by {@link CountingStatementInspector}, which Hibernate calls for every statement.
 * The count is the deterministic half of "how expensive is this call": unlike wall-clock time
 * it does not depend on host load, JIT warm-up or who else holds the single SQLite connection,
 * so it can be asserted in a test. An N+1 turns 3 statements into 33 no matter how fast the
 * machine is — that is what {@code RequestCostRatchetTest} pins.
 *
 * <p><b>Measurements nest, and every level counts.</b> One thread can be measured twice at once:
 * {@code RequestCostRatchetTest} measures around a MockMvc call while {@code RequestCostFilter}
 * measures the request from the inside. A single-slot ThreadLocal made the inner measurement
 * silently destroy the outer one — the ratchet then read 0 statements for every route and stayed
 * green, because zero is below every ceiling (found 2026-09-18, introduced with the filter).
 * {@link #record()} therefore increments every open measurement, and {@link #stop()} ends only
 * the innermost.
 *
 * <p>When no measurement is active {@link #record()} does nothing and allocates nothing — it
 * runs on every statement in production.
 */
public final class SqlStatementCounter {

    private static final ThreadLocal<Deque<int[]>> COUNTS = new ThreadLocal<>();

    private SqlStatementCounter() {
    }

    /** Begins a measurement on this thread, nested inside any measurement already running. */
    public static void start() {
        Deque<int[]> stack = COUNTS.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            COUNTS.set(stack);
        }
        stack.push(new int[1]);
    }

    /** Records one statement against every open measurement, or does nothing when none is active. */
    public static void record() {
        Deque<int[]> stack = COUNTS.get();
        if (stack == null) return;
        for (int[] counter : stack) {
            counter[0]++;
        }
    }

    /** Statements recorded by the innermost measurement, or {@code 0} when none is active. */
    public static int count() {
        Deque<int[]> stack = COUNTS.get();
        int[] innermost = stack == null ? null : stack.peek();
        return innermost == null ? 0 : innermost[0];
    }

    /** Whether this thread is currently measuring. */
    public static boolean isActive() {
        Deque<int[]> stack = COUNTS.get();
        return stack != null && !stack.isEmpty();
    }

    /** Ends the innermost measurement, freeing the ThreadLocal with the last one. */
    public static void stop() {
        Deque<int[]> stack = COUNTS.get();
        if (stack == null) return;
        stack.poll();
        if (stack.isEmpty()) {
            COUNTS.remove();
        }
    }
}
