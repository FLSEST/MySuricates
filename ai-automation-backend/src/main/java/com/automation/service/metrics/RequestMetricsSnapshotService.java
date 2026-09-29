package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.DayWindow;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes the measured request costs into {@code request_metrics_daily} and prunes old rows.
 *
 * <p>The measurement itself is in-memory and free of database access; only this job persists,
 * and it does so in short {@code TransactionTemplate} bursts, one row at a time. With
 * {@code maximum-pool-size: 1} a long transaction here would hold the connection every request
 * is queuing for — the very contention the metrics exist to reveal.
 *
 * <p>Three moments persist: the end-of-day job, a clean shutdown (a restart used to drop the whole
 * day so far — the dev backend restarts several times a day), and any direct call. Each window is
 * written under the day it measured ({@link RequestMetricsCollector#drainWindows()}), and a second
 * window of the same day is merged into the row rather than replacing it (TODO cab7d1d9).
 *
 * <p>Scheduling is off under the integration profile ({@code @EnableScheduling} is
 * {@code @Profile("!integration")}), so tests call {@link #persistWindows()} directly.
 */
@Service
@Slf4j
public class RequestMetricsSnapshotService {

    private final RequestMetricsCollector collector;
    private final RequestMetricDailyRepository repository;
    private final RequestMetricsConfig config;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RequestMetricsSnapshotService(RequestMetricsCollector collector,
                                         RequestMetricDailyRepository repository,
                                         RequestMetricsConfig config,
                                         PlatformTransactionManager transactionManager,
                                         Clock clock) {
        this.collector = collector;
        this.repository = repository;
        this.config = config;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Just before midnight, so the row covers the day it is named after. */
    @Scheduled(cron = "0 55 23 * * *")
    public void snapshotAtEndOfDay() {
        if (!config.isEnabled()) return;
        persistWindows();
        pruneOldSnapshots();
    }

    /**
     * Keeps what was measured since the last snapshot when the application stops. The context is
     * still intact at {@link ContextClosedEvent} (the ActivityLog writes its shutdown line at the
     * same point), the connection pool closes only afterwards. A failure is logged, never thrown:
     * losing the numbers must not stall a shutdown.
     */
    @EventListener(ContextClosedEvent.class)
    public void persistOnShutdown() {
        if (!config.isEnabled()) return;
        try {
            int written = persistWindows();
            if (written > 0) {
                log.info("Request metrics: {} routes written on shutdown", written);
            }
        } catch (RuntimeException e) {
            log.warn("Request metrics could not be written on shutdown: {}", e.getMessage());
        }
    }

    /**
     * Persists every pending window under the day it measured and starts the next one empty.
     * Returns how many rows were written. Repeatable: a window of a day that already has a row is
     * merged into it ({@link #merge}).
     */
    public int persistWindows() {
        List<DayWindow> windows = collector.drainWindows();
        int written = 0;
        for (DayWindow window : windows) {
            for (RouteSnapshot snapshot : window.routes()) {
                transactionTemplate.executeWithoutResult(status -> upsert(window.day(), snapshot));
                written++;
            }
            log.info("Request metrics: {} routes written for {}", window.routes().size(), window.day());
        }
        return written;
    }

    /** Drops snapshots past the retention window. {@code retentionDays = 0} disables this. */
    public int pruneOldSnapshots() {
        int retentionDays = config.getRetentionDays();
        if (retentionDays <= 0) return 0;
        LocalDate cutoff = LocalDate.now(clock).minusDays(retentionDays);
        Integer deleted = transactionTemplate.execute(status -> repository.deleteOlderThan(cutoff));
        if (deleted != null && deleted > 0) {
            log.info("Request metrics: {} snapshots older than {} deleted", deleted, cutoff);
        }
        return deleted == null ? 0 : deleted;
    }

    private void upsert(LocalDate day, RouteSnapshot snapshot) {
        RequestMetricDaily row = repository.findByDayAndRoute(day, snapshot.route())
                .map(existing -> merge(existing, snapshot))
                .orElseGet(() -> fresh(day, snapshot));
        row.setCreatedAt(Instant.now(clock));
        repository.save(row);
    }

    private static RequestMetricDaily fresh(LocalDate day, RouteSnapshot snapshot) {
        RequestMetricDaily row = RequestMetricDaily.builder().day(day).route(snapshot.route()).build();
        row.setCalls(snapshot.calls());
        row.setP50Ms(snapshot.p50Ms());
        row.setP95Ms(snapshot.p95Ms());
        row.setMaxMs(snapshot.maxMs());
        row.setP95WaitMs(snapshot.p95WaitMs());
        row.setWaitShare(snapshot.waitShare());
        row.setMaxSqlStatements(snapshot.maxSqlStatements());
        row.setAvgSqlStatements(snapshot.avgSqlStatements());
        row.setMaxResponseBytes(snapshot.maxResponseBytes());
        row.setAvgResponseBytes(snapshot.avgResponseBytes());
        return row;
    }

    /**
     * Folds a further window of the same day into its row. Calls add up, maxima stay maxima and
     * averages are exact when weighted by calls. Percentiles cannot be merged from two percentiles:
     * the calls-weighted mean is an approximation that can understate a day whose slow requests all
     * fell into the smaller window. Same for the wait share, which is weighted by calls instead of
     * by the (unstored) total time. Good enough for a day-over-day trend; the live window's numbers
     * stay exact.
     */
    static RequestMetricDaily merge(RequestMetricDaily row, RouteSnapshot snapshot) {
        long before = row.getCalls();
        long added = snapshot.calls();
        long total = before + added;
        if (total == 0) return row;

        row.setP50Ms(weighted(row.getP50Ms(), before, snapshot.p50Ms(), added, total));
        row.setP95Ms(weighted(row.getP95Ms(), before, snapshot.p95Ms(), added, total));
        row.setP95WaitMs(weighted(row.getP95WaitMs(), before, snapshot.p95WaitMs(), added, total));
        row.setWaitShare((row.getWaitShare() * before + snapshot.waitShare() * added) / total);
        row.setAvgSqlStatements((row.getAvgSqlStatements() * before + snapshot.avgSqlStatements() * added) / total);
        row.setAvgResponseBytes(weighted(row.getAvgResponseBytes(), before, snapshot.avgResponseBytes(), added, total));
        row.setMaxMs(Math.max(row.getMaxMs(), snapshot.maxMs()));
        row.setMaxSqlStatements(Math.max(row.getMaxSqlStatements(), snapshot.maxSqlStatements()));
        row.setMaxResponseBytes(Math.max(row.getMaxResponseBytes(), snapshot.maxResponseBytes()));
        row.setCalls(total);
        return row;
    }

    private static long weighted(long a, long weightA, long b, long weightB, long total) {
        return Math.round(((double) a * weightA + (double) b * weightB) / total);
    }
}
