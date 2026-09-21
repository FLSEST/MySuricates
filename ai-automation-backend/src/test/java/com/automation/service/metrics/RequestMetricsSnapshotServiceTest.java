package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.repository.RequestMetricDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
        collector = new RequestMetricsCollector();
        repository = mock(RequestMetricDailyRepository.class);
        config = new RequestMetricsConfig();
        transactionManager = mock(PlatformTransactionManager.class);
        service = new RequestMetricsSnapshotService(collector, repository, config, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findByDayAndRoute(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void writesOneRowPerRouteAndStartsTheNextWindowEmpty() {
        collector.record(ROUTE, 10, 2, 3, 1_000);
        collector.record(ROUTE, 30, 6, 5, 3_000);
        collector.record("GET /api/v1/runs", 5, 0, 1, 100);

        assertThat(service.snapshotToday()).isEqualTo(2);

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

    /** Repeatable within a day: the existing row is updated, not duplicated. */
    @Test
    void secondSnapshotOfTheDayUpdatesTheExistingRow() {
        RequestMetricDaily existing = RequestMetricDaily.builder().id("row-1").day(TODAY).route(ROUTE).calls(99).build();
        when(repository.findByDayAndRoute(TODAY, ROUTE)).thenReturn(Optional.of(existing));
        collector.record(ROUTE, 10, 0, 1, 100);

        service.snapshotToday();

        verify(repository).save(existing);
        assertThat(existing.getId()).isEqualTo("row-1");
        assertThat(existing.getCalls()).isEqualTo(1);
    }

    @Test
    void emptyWindowWritesNothing() {
        assertThat(service.snapshotToday()).isZero();

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
