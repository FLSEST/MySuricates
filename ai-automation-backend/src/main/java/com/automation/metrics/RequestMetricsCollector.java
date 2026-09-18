package com.automation.metrics;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 */
@Component
public class RequestMetricsCollector {

    /** Durations kept per route for the percentile estimate. */
    static final int WINDOW = 1024;

    /** Upper bound on distinct route keys held in memory. */
    static final int MAX_ROUTES = 200;

    private final Map<String, RouteStats> byRoute = new ConcurrentHashMap<>();

    public void record(String route, long totalMs, long waitMs, int sqlStatements, long responseBytes) {
        RouteStats stats = byRoute.get(route);
        if (stats == null) {
            if (byRoute.size() >= MAX_ROUTES) {
                return; // cap reached: drop rather than grow without bound
            }
            stats = byRoute.computeIfAbsent(route, key -> new RouteStats());
        }
        stats.add(totalMs, waitMs, sqlStatements, responseBytes);
    }

    /** Current numbers per route, newest state, without clearing. */
    public List<RouteSnapshot> snapshot() {
        List<RouteSnapshot> result = new ArrayList<>(byRoute.size());
        byRoute.forEach((route, stats) -> result.add(stats.toSnapshot(route)));
        result.sort((a, b) -> Long.compare(b.p95Ms(), a.p95Ms()));
        return result;
    }

    /** Snapshot for the daily row, then start the next window empty. */
    public List<RouteSnapshot> snapshotAndReset() {
        List<RouteSnapshot> result = snapshot();
        byRoute.clear();
        return result;
    }

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
