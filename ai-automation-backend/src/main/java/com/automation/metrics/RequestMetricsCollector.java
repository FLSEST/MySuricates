package com.automation.metrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Rolling per-route cost statistics, held in memory.
 *
 * <p>Percentiles, never averages: a mean hides the one request in fifty that takes ten seconds,
 * and that request is the whole complaint. Each route keeps its most recent durations in a ring
 * buffer and reports p50/p95 from those.
 *
 * <p>Cardinality is bounded twice over — keys are route <em>templates</em> ({@code /api/v1/todos/{id}},
 * never the URL with the id in it) and {@link #MAX_ROUTES} caps the map, so a path that slips
 * through unparameterised cannot grow the map without limit.
 *
 * <p>Nothing here touches the database: the collector is fed from the request thread, and the
 * daily snapshot job reads it. With {@code maximum-pool-size: 1}, measurement that needed a
 * connection would compete with the very requests it measures.
 *
 * <p>A window belongs to one calendar day. The first request of a new day closes the running
 * window under the day it measured, so a snapshot job that runs late — the machine slept through
 * 23:55 and caught up at 01:04 — no longer files yesterday's numbers under today (TODO cab7d1d9).
 */
@Component
public class RequestMetricsCollector {

    /** Durations kept per route for the percentile estimate. */
    static final int WINDOW = 1024;

    /** Upper bound on distinct route keys held in memory. */
    static final int MAX_ROUTES = 200;

    /** Closed day windows kept until the snapshot job persists them; the oldest go first. */
    static final int MAX_CLOSED_WINDOWS = 7;

    private final Clock clock;

    /**
     * Request threads record under the read lock, a day change or a drain swaps the window under the
     * write lock — so no request lands in a window that has already been handed to the snapshot.
     */
    private final ReadWriteLock windowLock = new ReentrantReadWriteLock();

    private Map<String, RouteStats> byRoute = new ConcurrentHashMap<>();
    /** Day the running window measures; set by the first request after a reset. */
    private LocalDate windowDay;
    /** Since when the running window counts: construction, the last drain, or midnight of a new day. */
    private Instant windowOpenedAt;
    private final List<DayWindow> closedWindows = new ArrayList<>();

    /** System clock — for tests and callers outside the Spring context. */
    public RequestMetricsCollector() {
        this(Clock.systemDefaultZone());
    }

    @Autowired
    public RequestMetricsCollector(Clock clock) {
        this.clock = clock;
        this.windowOpenedAt = clock.instant();
    }

    public void record(String route, long totalMs, long waitMs, int sqlStatements, long responseBytes) {
        LocalDate today = LocalDate.now(clock);
        windowLock.readLock().lock();
        try {
            if (today.equals(windowDay)) {
                add(route, totalMs, waitMs, sqlStatements, responseBytes);
                return;
            }
        } finally {
            windowLock.readLock().unlock();
        }

        windowLock.writeLock().lock();
        try {
            rollOverTo(today);
            add(route, totalMs, waitMs, sqlStatements, responseBytes);
        } finally {
            windowLock.writeLock().unlock();
        }
    }

    private void add(String route, long totalMs, long waitMs, int sqlStatements, long responseBytes) {
        RouteStats stats = byRoute.get(route);
        if (stats == null) {
            if (byRoute.size() >= MAX_ROUTES) {
                return; // cap reached: drop rather than grow without bound
            }
            stats = byRoute.computeIfAbsent(route, key -> new RouteStats());
        }
        stats.add(totalMs, waitMs, sqlStatements, responseBytes);
    }

    /** Called under the write lock. Closes a window of an earlier day, opens one for {@code today}. */
    private void rollOverTo(LocalDate today) {
        if (today.equals(windowDay)) {
            return; // another thread rolled over while this one waited for the lock
        }
        if (windowDay != null && !byRoute.isEmpty()) {
            closedWindows.add(new DayWindow(windowDay, snapshotOf(byRoute)));
            if (closedWindows.size() > MAX_CLOSED_WINDOWS) {
                closedWindows.remove(0);
            }
            byRoute = new ConcurrentHashMap<>();
            windowOpenedAt = today.atStartOfDay(clock.getZone()).toInstant();
        }
        windowDay = today;
    }

    /** Current numbers per route of the running window, without clearing. */
    public List<RouteSnapshot> snapshot() {
        windowLock.readLock().lock();
        try {
            return snapshotOf(byRoute);
        } finally {
            windowLock.readLock().unlock();
        }
    }

    /** Since when the running window counts — its numbers cover the time from here to now. */
    public Instant windowOpenedAt() {
        windowLock.readLock().lock();
        try {
            return windowOpenedAt;
        } finally {
            windowLock.readLock().unlock();
        }
    }

    /**
     * Every window not yet persisted, each under the day it measured, oldest first; then starts
     * the next window empty. Nothing measured means an empty list.
     */
    public List<DayWindow> drainWindows() {
        windowLock.writeLock().lock();
        try {
            List<DayWindow> drained = new ArrayList<>(closedWindows);
            if (windowDay != null && !byRoute.isEmpty()) {
                drained.add(new DayWindow(windowDay, snapshotOf(byRoute)));
            }
            closedWindows.clear();
            byRoute = new ConcurrentHashMap<>();
            windowDay = null;
            windowOpenedAt = clock.instant();
            return drained;
        } finally {
            windowLock.writeLock().unlock();
        }
    }

    private static List<RouteSnapshot> snapshotOf(Map<String, RouteStats> window) {
        List<RouteSnapshot> result = new ArrayList<>(window.size());
        window.forEach((route, stats) -> result.add(stats.toSnapshot(route)));
        result.sort((a, b) -> Long.compare(b.p95Ms(), a.p95Ms()));
        return result;
    }

    /** The routes one calendar day measured — the unit a daily row is written from. */
    public record DayWindow(LocalDate day, List<RouteSnapshot> routes) {}

    /** What one route cost over the window. */
    public record RouteSnapshot(
            String route,
            long calls,
            long p50Ms,
            long p95Ms,
            long maxMs,
            long p95WaitMs,
            double waitShare,
            int maxSqlStatements,
            double avgSqlStatements,
            long maxResponseBytes,
            long avgResponseBytes) {}

    private static final class RouteStats {

        private final long[] durations = new long[WINDOW];
        private final long[] waits = new long[WINDOW];
        private int filled;
        private int next;

        private long calls;
        private long totalMsSum;
        private long waitMsSum;
        private long maxMs;
        private long sqlSum;
        private int maxSql;
        private long bytesSum;
        private long maxBytes;

        synchronized void add(long totalMs, long waitMs, int sqlStatements, long responseBytes) {
            durations[next] = totalMs;
            waits[next] = waitMs;
            next = (next + 1) % WINDOW;
            if (filled < WINDOW) filled++;

            calls++;
            totalMsSum += totalMs;
            waitMsSum += waitMs;
            if (totalMs > maxMs) maxMs = totalMs;
            sqlSum += sqlStatements;
            if (sqlStatements > maxSql) maxSql = sqlStatements;
            bytesSum += responseBytes;
            if (responseBytes > maxBytes) maxBytes = responseBytes;
        }

        synchronized RouteSnapshot toSnapshot(String route) {
            long[] sortedDurations = Arrays.copyOf(durations, filled);
            long[] sortedWaits = Arrays.copyOf(waits, filled);
            Arrays.sort(sortedDurations);
            Arrays.sort(sortedWaits);
            return new RouteSnapshot(
                    route,
                    calls,
                    percentile(sortedDurations, 50),
                    percentile(sortedDurations, 95),
                    maxMs,
                    percentile(sortedWaits, 95),
                    totalMsSum == 0 ? 0d : (double) waitMsSum / totalMsSum,
                    maxSql,
                    calls == 0 ? 0d : (double) sqlSum / calls,
                    maxBytes,
                    calls == 0 ? 0L : bytesSum / calls);
        }

        /** Nearest-rank percentile over the already sorted window. */
        private static long percentile(long[] sorted, int percentile) {
            if (sorted.length == 0) return 0L;
            int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
            return sorted[Math.min(Math.max(rank - 1, 0), sorted.length - 1)];
        }
    }
}
