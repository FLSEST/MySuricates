package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.ActivityLog;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.repository.RequestMetricDailyRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestMetricsDriftWatchdogServiceTest {

    private static final String ROUTE = "GET /api/v1/todos";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private RequestMetricsCollector collector;
    private RequestMetricDailyRepository repository;
    private RequestMetricsConfig config;
    private ActivityLogService activityLogService;
    private SimpMessagingTemplate messagingTemplate;
    private MutableClock clock;
    private RequestMetricsDriftWatchdogService watchdog;

    /** A clock the test can move to the next day. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        collector = new RequestMetricsCollector();
        repository = mock(RequestMetricDailyRepository.class);
        config = new RequestMetricsConfig();
        activityLogService = mock(ActivityLogService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        clock = new MutableClock(Instant.parse("2026-09-21T10:07:00Z"));
        watchdog = new RequestMetricsDriftWatchdogService(collector, repository, config, activityLogService,
                messagingTemplate, mock(PlatformTransactionManager.class), clock);
    }

    private void recordCalls(String route, int calls, long totalMs) {
        IntStream.range(0, calls).forEach(i -> collector.record(route, totalMs, 0, 3, 100));
    }

    private final List<RequestMetricDaily> storedRows = new ArrayList<>();

    private void baseline(String route, long p95Ms) {
        storedRows.add(RequestMetricDaily.builder().route(route).day(TODAY.minusDays(2)).p95Ms(p95Ms).avgSqlStatements(3).build());
        storedRows.add(RequestMetricDaily.builder().route(route).day(TODAY.minusDays(1)).p95Ms(p95Ms).avgSqlStatements(3).build());
        storedRows.sort(Comparator.comparing(RequestMetricDaily::getDay));
        when(repository.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(List.copyOf(storedRows));
    }

    @Test
    void noLiveMeasurementsMeansNoBaselineQuery() {
        assertThat(watchdog.detectDrift()).isEmpty();

        verifyNoInteractions(repository);
    }

    /** Today is what is being judged — the baseline ends yesterday. */
    @Test
    void baselineIsTheConfiguredDaysBeforeToday() {
        config.setBaselineDays(5);
        recordCalls(ROUTE, 1, 10);

        watchdog.detectDrift();

        verify(repository).findByDayBetweenOrderByDayAsc(TODAY.minusDays(5), TODAY.minusDays(1));
    }

    /**
     * One query for the whole window, however many routes are live — one per route held the
     * single connection for about 70 s (2026-09-24). Rows of routes without traffic today are
     * dropped, and each route keeps its rows in day order.
     */
    @Test
    void baselineIsOneQueryForAllRoutesFilteredToTodaysRoutes() {
        String runs = "GET /api/v1/runs";
        IntStream.range(0, 20).forEach(i -> recordCalls("GET /api/v1/route-" + i, 1, 10));
        recordCalls(ROUTE, 30, 300);
        recordCalls(runs, 30, 300);
        storedRows.add(RequestMetricDaily.builder().route(ROUTE).day(TODAY.minusDays(3)).p95Ms(100).avgSqlStatements(3).build());
        storedRows.add(RequestMetricDaily.builder().route("GET /api/v1/gone").day(TODAY.minusDays(3)).p95Ms(1).avgSqlStatements(3).build());
        storedRows.add(RequestMetricDaily.builder().route(runs).day(TODAY.minusDays(2)).p95Ms(290).avgSqlStatements(3).build());
        storedRows.add(RequestMetricDaily.builder().route(ROUTE).day(TODAY.minusDays(2)).p95Ms(100).avgSqlStatements(3).build());
        storedRows.add(RequestMetricDaily.builder().route(ROUTE).day(TODAY.minusDays(1)).p95Ms(120).avgSqlStatements(3).build());
        when(repository.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(List.copyOf(storedRows));

        List<RouteDrift> drifting = watchdog.detectDrift();

        verify(repository, times(1)).findByDayBetweenOrderByDayAsc(any(), any());
        verify(repository, never()).findByRouteAndDayBetweenOrderByDayAsc(any(), any(), any());
        assertThat(drifting).singleElement().satisfies(drift -> {
            assertThat(drift.route()).isEqualTo(ROUTE);
            assertThat(drift.baselineP95Ms()).isEqualTo(100);
        });
    }

    @Test
    void detectsARouteSlowerThanItsBaseline() {
        recordCalls(ROUTE, 30, 300);
        baseline(ROUTE, 100);

        List<RouteDrift> drifting = watchdog.detectDrift();

        assertThat(drifting).singleElement().satisfies(drift -> {
            assertThat(drift.route()).isEqualTo(ROUTE);
            assertThat(drift.baselineP95Ms()).isEqualTo(100);
            assertThat(drift.currentP95Ms()).isEqualTo(300);
        });
        // Detecting alone reports nothing.
        verifyNoInteractions(activityLogService, messagingTemplate);
    }

    @Test
    void checkReportsDriftToActivityLogAndSocket() {
        recordCalls(ROUTE, 30, 300);
        baseline(ROUTE, 100);

        watchdog.checkForDrift();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(ActivityLog.LogLevel.WARN), eq(ActivityLog.LogCategory.SYSTEM),
                org.mockito.ArgumentMatchers.startsWith("Request cost drift — " + ROUTE),
                eq("RequestMetricsDriftWatchdog"), isNull(), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("route", ROUTE)
                .containsEntry("baseline_p95_ms", 100L)
                .containsEntry("current_p95_ms", 300L)
                .containsEntry("factor", 3.0d)
                .containsEntry("calls", 30L);
        verify(messagingTemplate).convertAndSend(eq("/topic/activity-log"), any(Object.class));
    }

    /** An alert that repeats hourly trains people to ignore it. */
    @Test
    void sameRouteIsReportedOncePerDayAndAgainTheNextDay() {
        recordCalls(ROUTE, 30, 300);
        baseline(ROUTE, 100);

        watchdog.checkForDrift();
        watchdog.checkForDrift();
        verify(activityLogService, times(1)).log(any(), any(), anyString(), anyString(), any(), anyMap());

        clock.now = Instant.parse("2026-09-22T00:07:00Z");
        watchdog.checkForDrift();
        verify(activityLogService, times(2)).log(any(), any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void failingActivityLogDoesNotStopTheCheck() {
        recordCalls(ROUTE, 30, 300);
        recordCalls("GET /api/v1/runs", 30, 300);
        baseline(ROUTE, 100);
        baseline("GET /api/v1/runs", 100);
        when(activityLogService.log(any(), any(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new RuntimeException("SQLITE_BUSY"));
        doThrow(new RuntimeException("no broker")).when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        watchdog.checkForDrift();

        verify(activityLogService, times(2)).log(any(), any(), anyString(), anyString(), any(), anyMap());
    }

    @Test
    void switchedOffChecksNothing() {
        recordCalls(ROUTE, 30, 300);
        baseline(ROUTE, 100);

        config.setDriftDetectionEnabled(false);
        watchdog.checkForDrift();
        config.setDriftDetectionEnabled(true);
        config.setEnabled(false);
        watchdog.checkForDrift();

        verify(repository, never()).findByDayBetweenOrderByDayAsc(any(), any());
        verifyNoInteractions(activityLogService, messagingTemplate);
    }
}
