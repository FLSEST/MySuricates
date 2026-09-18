package com.automation.metrics;

/**
 * Accumulates the time one thread spends waiting for a JDBC connection.
 *
 * <p>This is the number that matters most in this architecture. With
 * {@code maximum-pool-size: 1} the common cause of a slow view is not its own query but
 * head-of-line blocking: some other request or a workflow step holds the single SQLite
 * connection while this request queues for it. Without separating wait from work, the
 * blame lands on whichever route happened to ask next.
 *
 * <p>Hikari's {@code hikaricp.connections.acquire} timer only knows the global picture, so
 * the per-request share is measured in {@link TimingDataSource} around
 * {@code getConnection()} and accumulated here.
 *
 * <p>Same contract as {@link SqlStatementCounter}: per thread, no nesting, and a no-op that
 * allocates nothing when no measurement is active.
 */
public final class ConnectionWaitCounter {

    private static final ThreadLocal<long[]> WAIT_NANOS = new ThreadLocal<>();

    private ConnectionWaitCounter() {
    }

    /** Begins a measurement on this thread, discarding any previous one. */
    public static void start() {
        WAIT_NANOS.set(new long[1]);
    }

    /** Adds one connection acquisition, or does nothing when no measurement is active. */
    public static void record(long nanos) {
        long[] counter = WAIT_NANOS.get();
        if (counter != null) {
            counter[0] += nanos;
        }
    }

    /** Nanoseconds waited since {@link #start()}, or {@code 0} when no measurement is active. */
    public static long waitedNanos() {
        long[] counter = WAIT_NANOS.get();
        return counter == null ? 0L : counter[0];
    }

    /** Whether this thread is currently measuring. */
    public static boolean isActive() {
        return WAIT_NANOS.get() != null;
    }

    /** Ends the measurement and frees the ThreadLocal. Safe to call without a prior start. */
    public static void stop() {
        WAIT_NANOS.remove();
    }
}
