package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes the day's request costs into {@code request_metrics_daily} and prunes old rows.
 *
 * <p>The measurement itself is in-memory and free of database access; only this job persists,
 * and it does so in short {@code TransactionTemplate} bursts, one row at a time. With
 * {@code maximum-pool-size: 1} a long transaction here would hold the connection every request
 * is queuing for — the very contention the metrics exist to reveal.
 *
 * <p>Scheduling is off under the integration profile ({@code @EnableScheduling} is
 * {@code @Profile("!integration")}), so tests call {@link #snapshotToday()} directly.
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
        snapshotToday();
        pruneOldSnapshots();
    }

    /**
     * Persists the current window and starts the next one empty. Returns how many routes were
     * written — repeatable within a day: an existing row for the same day and route is updated.
     */
    public int snapshotToday() {
        List<RouteSnapshot> snapshots = collector.snapshotAndReset();
        LocalDate today = LocalDate.now(clock);
        for (RouteSnapshot snapshot : snapshots) {
            transactionTemplate.executeWithoutResult(status -> upsert(today, snapshot));
        }
        if (!snapshots.isEmpty()) {
            log.info("Request metrics: {} routes written for {}", snapshots.size(), today);
        }
        return snapshots.size();
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
                .orElseGet(() -> RequestMetricDaily.builder().day(day).route(snapshot.route()).build());
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
        row.setCreatedAt(Instant.now(clock));
        repository.save(row);
    }
}
