package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.DayWindow;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestMetricsSnapshotServiceTest {

    private static final String ROUTE = "GET /api/v1/todos";
    private static final Instant NOW = Instant.parse("2026-09-21T21:55:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private RequestMetricsCollector collector;
    private RequestMetricDailyRepository repository;
    private RequestMetricsConfig config;
    private PlatformTransactionManager transactionManager;
    private RequestMetricsSnapshotService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        collector = new RequestMetricsCollector(clock);
        repository = mock(RequestMetricDailyRepository.class);
        config = new RequestMetricsConfig();
        transactionManager = mock(PlatformTransactionManager.class);
        service = new RequestMetricsSnapshotService(collector, repository, config, transactionManager, clock);
        when(repository.findByDayAndRoute(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void writesOneRowPerRouteAndStartsTheNextWindowEmpty() {
        collector.record(ROUTE, 10, 2, 3, 1_000);
        collector.record(ROUTE, 30, 6, 5, 3_000);
        collector.record("GET /api/v1/runs", 5, 0, 1, 100);

        assertThat(service.persistWindows()).isEqualTo(2);

        ArgumentCaptor<RequestMetricDaily> saved = ArgumentCaptor.forClass(RequestMetricDaily.class);
        verify(repository, times(2)).save(saved.capture());
        RequestMetricDaily todos = saved.getAllValues().stream()
                .filter(row -> row.getRoute().equals(ROUTE)).findFirst().orElseThrow();
        assertThat(todos.getDay()).isEqualTo(TODAY);
        assertThat(todos.getCalls()).isEqualTo(2);
        assertThat(todos.getMaxMs()).isEqualTo(30);
        assertThat(todos.getP95Ms()).isEqualTo(30);
        assertThat(todos.getWaitShare()).isEqualTo(0.2d);
        assertThat(todos.getMaxSqlStatements()).isEqualTo(5);
        assertThat(todos.getAvgSqlStatements()).isEqualTo(4.0d);
        assertThat(todos.getMaxResponseBytes()).isEqualTo(3_000);
        assertThat(todos.getAvgResponseBytes()).isEqualTo(2_000);
        assertThat(todos.getCreatedAt()).isEqualTo(NOW);
        // One short transaction per row, never one long one across all of them.
        verify(transactionManager, times(2)).commit(any());

        assertThat(collector.snapshot()).isEmpty();
    }

    /**
     * Repeatable within a day: a restart persists mid-day, the end-of-day job the rest. The second
     * window is folded into the row — neither duplicated nor overwriting the first (TODO cab7d1d9).
     */
    @Test
    void secondSnapshotOfTheDayIsMergedIntoTheExistingRow() {
        RequestMetricDaily existing = RequestMetricDaily.builder().id("row-1").day(TODAY).route(ROUTE)
                .calls(3).p50Ms(40).p95Ms(100).maxMs(200).p95WaitMs(20).waitShare(0.5)
                .maxSqlStatements(9).avgSqlStatements(6.0).maxResponseBytes(5_000).avgResponseBytes(4_000)
                .build();
        when(repository.findByDayAndRoute(TODAY, ROUTE)).thenReturn(Optional.of(existing));
        collector.record(ROUTE, 20, 0, 2, 1_000);

        service.persistWindows();

        verify(repository).save(existing);
        assertThat(existing.getId()).isEqualTo("row-1");
        assertThat(existing.getCalls()).isEqualTo(4);
        // Maxima stay maxima.
        assertThat(existing.getMaxMs()).isEqualTo(200);
        assertThat(existing.getMaxSqlStatements()).isEqualTo(9);
        assertThat(existing.getMaxResponseBytes()).isEqualTo(5_000);
        // Averages exact, percentiles and wait share weighted by calls: (3*100 + 1*20) / 4 = 80.
        assertThat(existing.getP95Ms()).isEqualTo(80);
        assertThat(existing.getP50Ms()).isEqualTo(35);
        assertThat(existing.getP95WaitMs()).isEqualTo(15);
        assertThat(existing.getWaitShare()).isEqualTo(0.375d);
        assertThat(existing.getAvgSqlStatements()).isEqualTo(5.0d);
        assertThat(existing.getAvgResponseBytes()).isEqualTo(3_250);
        assertThat(existing.getCreatedAt()).isEqualTo(NOW);
    }

    /** Every pending window goes under the day it measured, not under the day the job ran. */
    @Test
    void windowsOfEarlierDaysKeepTheirDay() {
        RequestMetricsCollector lateCollector = mock(RequestMetricsCollector.class);
        RouteSnapshot snapshot = new RouteSnapshot(ROUTE, 1, 10, 10, 10, 0, 0, 1, 1, 100, 100);
        when(lateCollector.drainWindows()).thenReturn(List.of(
                new DayWindow(TODAY.minusDays(1), List.of(snapshot)),
                new DayWindow(TODAY, List.of(snapshot))));
        service = new RequestMetricsSnapshotService(lateCollector, repository, config, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.persistWindows()).isEqualTo(2);

        ArgumentCaptor<RequestMetricDaily> saved = ArgumentCaptor.forClass(RequestMetricDaily.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(RequestMetricDaily::getDay)
                .containsExactly(TODAY.minusDays(1), TODAY);
    }

    /** A restart used to drop the whole day so far; a clean shutdown now persists it. */
    @Test
    void shutdownPersistsTheRunningWindow() {
        collector.record(ROUTE, 10, 0, 1, 100);

        service.persistOnShutdown();

        verify(repository).save(any());
        assertThat(collector.snapshot()).isEmpty();
    }

    /** Losing the numbers is acceptable, stalling the shutdown is not. */
    @Test
    void shutdownSwallowsAFailedWrite() {
        collector.record(ROUTE, 10, 0, 1, 100);
        when(repository.findByDayAndRoute(any(), any())).thenThrow(new IllegalStateException("pool closed"));

        service.persistOnShutdown();
    }

    @Test
    void shutdownWritesNothingWhenMeasurementIsOff() {
        config.setEnabled(false);
        collector.record(ROUTE, 10, 0, 1, 100);

        service.persistOnShutdown();

        verifyNoInteractions(repository);
    }

    @Test
    void emptyWindowWritesNothing() {
        assertThat(service.persistWindows()).isZero();

        verify(repository, never()).save(any());
    }

    @Test
    void pruneDeletesRowsOlderThanTheRetention() {
        config.setRetentionDays(30);
        when(repository.deleteOlderThan(TODAY.minusDays(30))).thenReturn(4);

        assertThat(service.pruneOldSnapshots()).isEqualTo(4);
    }

    @Test
    void retentionZeroDisablesPruning() {
        config.setRetentionDays(0);

        assertThat(service.pruneOldSnapshots()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void endOfDayJobSnapshotsAndPrunes() {
        collector.record(ROUTE, 10, 0, 1, 100);

        service.snapshotAtEndOfDay();

        verify(repository).save(any());
        verify(repository).deleteOlderThan(TODAY.minusDays(90));
    }

    @Test
    void endOfDayJobDoesNothingWhenMeasurementIsOff() {
        config.setEnabled(false);
        collector.record(ROUTE, 10, 0, 1, 100);

        service.snapshotAtEndOfDay();

        verifyNoInteractions(repository);
        assertThat(collector.snapshot()).hasSize(1);
    }
}
