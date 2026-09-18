package com.automation.metrics;

/**
 * Counts the SQL statements one thread issues while a measurement is active.
 *
 * <p>Fed by {@link CountingStatementInspector}, which Hibernate calls for every statement.
 * The count is the deterministic half of "how expensive is this call": unlike wall-clock time
 * it does not depend on host load, JIT warm-up or who else holds the single SQLite connection,
 * so it can be asserted in a test. An N+1 turns 3 statements into 33 no matter how fast the
 * machine is — that is what {@code RequestCostRatchetTest} pins.
 *
 * <p>Measurements are per thread and never nested: {@link #start()} resets, {@link #stop()}
 * removes the ThreadLocal. Requests run on virtual threads, so the ThreadLocal dies with the
 * thread anyway; {@link #stop()} matters for the pooled threads that outlive a measurement.
 *
 * <p>When no measurement is active {@link #record()} does nothing and allocates nothing — it
 * runs on every statement in production.
 */
public final class SqlStatementCounter {

    private static final ThreadLocal<int[]> COUNT = new ThreadLocal<>();

    private SqlStatementCounter() {
    }

    /** Begins a measurement on this thread, discarding any previous one. */
    public static void start() {
        COUNT.set(new int[1]);
    }

    /** Records one statement, or does nothing when no measurement is active. */
    public static void record() {
        int[] counter = COUNT.get();
        if (counter != null) {
            counter[0]++;
        }
    }

    /** Statements recorded since {@link #start()}, or {@code 0} when no measurement is active. */
    public static int count() {
        int[] counter = COUNT.get();
        return counter == null ? 0 : counter[0];
    }

    /** Whether this thread is currently measuring. */
    public static boolean isActive() {
        return COUNT.get() != null;
    }

    /** Ends the measurement and frees the ThreadLocal. Safe to call without a prior start. */
    public static void stop() {
        COUNT.remove();
    }
}
