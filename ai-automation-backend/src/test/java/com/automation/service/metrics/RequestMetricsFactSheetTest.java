package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestMetricsFactSheetTest {

    private static final Instant NOW = Instant.parse("2026-09-28T20:18:00Z");
    private static final String NO_HISTORY =
            "NICHT GEMESSEN: request_metrics_history (keine gespeicherten Tageswerte der letzten 30 Tage)";

    private final RequestMetricsQueryService queryService = mock(RequestMetricsQueryService.class);
    private final RequestMetricsDriftWatchdogService watchdog = mock(RequestMetricsDriftWatchdogService.class);
    private final RequestMetricsConfig config = new RequestMetricsConfig();
    private final RequestMetricsFactSheet factSheet =
            new RequestMetricsFactSheet(queryService, watchdog, config, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(queryService.liveWindowOpenedAt()).thenReturn(Instant.parse("2026-09-28T19:47:13.123Z"));
        when(queryService.recentDays(anyInt())).thenReturn(List.of());
        config.setDriftMinCalls(20);
    }

    private static RouteSnapshot route(String name, long p95, int maxSql, double waitShare, long maxBytes) {
        return route(name, 50, p95, maxSql, waitShare, maxBytes);
    }

    private static RouteSnapshot route(String name, long calls, long p95, int maxSql, double waitShare, long maxBytes) {
        return new RouteSnapshot(name, calls, p95 / 2, p95, p95, 0, waitShare, maxSql, maxSql / 2d, maxBytes, maxBytes / 2);
    }

    /** A gap is stated, never left out — an absent line would read to the AI as "fine". */
    @Test
    void noMeasurementsIsStatedExplicitly() {
        when(queryService.liveWindow()).thenReturn(List.of());

        assertThat(factSheet.render()).isEqualTo(
                "NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)\n" + NO_HISTORY);
        verifyNoInteractions(watchdog);
    }

    @Test
    void rendersOneFactPerMeasuredValue() {
        when(queryService.liveWindow()).thenReturn(List.of(
                route("GET /api/v1/workflows", 900, 4, 0.1, 2_000),
                route("GET /api/v1/todos", 300, 41, 0.05, 80_000),
                route("GET /api/v1/runs", 100, 2, 0.625, 500)));
        when(watchdog.detectDrift()).thenReturn(List.of());

        assertThat(factSheet.render()).isEqualTo("""
                FAKT request_metrics_routes_measured: 3
                FAKT request_metrics_slowest_route: GET /api/v1/workflows
                FAKT request_metrics_slowest_p95_ms: 900
                FAKT request_metrics_max_sql_route: GET /api/v1/todos
                FAKT request_metrics_max_sql_statements: 41
                FAKT request_metrics_max_wait_share_percent: 62.5
                FAKT request_metrics_max_wait_share_route: GET /api/v1/runs
                FAKT request_metrics_max_response_bytes: 80000
                FAKT request_metrics_drifting_routes: 0
                FAKT request_metrics_drift_detail: keine
                FAKT request_metrics_window_start: 2026-09-28T19:47:13Z (30 min)
                FAKT request_metrics_window_calls: 150
                FAKT request_metrics_min_calls: 20
                FAKT request_metrics_routes_below_min_calls: 0
                FAKT request_metrics_top_p95_1: GET /api/v1/workflows | p95 900 ms | p50 450 ms | 50 Aufrufe | Wartezeit 10.0 % | SQL 2.0
                FAKT request_metrics_top_p95_2: GET /api/v1/todos | p95 300 ms | p50 150 ms | 50 Aufrufe | Wartezeit 5.0 % | SQL 20.5
                FAKT request_metrics_top_p95_3: GET /api/v1/runs | p95 100 ms | p50 50 ms | 50 Aufrufe | Wartezeit 62.5 % | SQL 1.0
                FAKT request_metrics_top_wait_1: GET /api/v1/runs | p95 100 ms | p50 50 ms | 50 Aufrufe | Wartezeit 62.5 % | SQL 1.0
                FAKT request_metrics_top_wait_2: GET /api/v1/workflows | p95 900 ms | p50 450 ms | 50 Aufrufe | Wartezeit 10.0 % | SQL 2.0
                FAKT request_metrics_top_wait_3: GET /api/v1/todos | p95 300 ms | p50 150 ms | 50 Aufrufe | Wartezeit 5.0 % | SQL 20.5
                """ + NO_HISTORY);
    }

    /** One cold request after a restart is counted, never ranked as "the slowest route". */
    @Test
    void routesBelowTheMinimumAreCountedButNotRanked() {
        when(queryService.liveWindow()).thenReturn(List.of(
                route("GET /api/v1/workflows/{id}/export/bpmn", 2, 497, 3, 0.0, 1_000),
                route("GET /api/v1/todos", 40, 120, 3, 0.2, 1_000)));
        when(watchdog.detectDrift()).thenReturn(List.of());

        String facts = factSheet.render();

        assertThat(facts).contains("FAKT request_metrics_routes_below_min_calls: 1\n")
                .contains("FAKT request_metrics_top_p95_1: GET /api/v1/todos |")
                .doesNotContain("request_metrics_top_p95_2")
                .doesNotContain("top_p95_1: GET /api/v1/workflows/{id}/export/bpmn");
    }

    @Test
    void noRankableRouteIsStatedExplicitly() {
        when(queryService.liveWindow()).thenReturn(List.of(route("GET /api/v1/todos", 3, 120, 3, 0.2, 1_000)));
        when(watchdog.detectDrift()).thenReturn(List.of());

        assertThat(factSheet.render()).contains(
                "NICHT GEMESSEN: request_metrics_top (keine Route mit mindestens 20 Aufrufen im Live-Fenster)");
    }

    @Test
    void driftingRoutesAreListedWithTheirFactor() {
        when(queryService.liveWindow()).thenReturn(List.of(route("GET /api/v1/todos", 300, 3, 0.1, 100)));
        when(watchdog.detectDrift()).thenReturn(List.of(
                new RouteDrift("GET /api/v1/todos", 100, 300, 3, 3, 0.1, 50),
                new RouteDrift("GET /api/v1/runs", 40, 100, 3, 3, 0.1, 50)));

        String facts = factSheet.render();

        assertThat(facts).contains("FAKT request_metrics_drifting_routes: 2\n")
                .contains("FAKT request_metrics_drift_detail: GET /api/v1/todos x3.0; GET /api/v1/runs x2.5\n");
    }

    /** The history is rendered even when the live window is empty — right after a restart it is all there is. */
    @Test
    void historyIsRenderedWithoutALiveWindow() {
        when(queryService.liveWindow()).thenReturn(List.of());
        when(queryService.recentDays(RequestMetricsHistoryFacts.HISTORY_DAYS)).thenReturn(List.of(
                RequestMetricDaily.builder().day(LocalDate.of(2026, 9, 27)).route("GET /api/v1/todos")
                        .calls(10).p50Ms(10).p95Ms(20).maxMs(30).build()));

        assertThat(factSheet.render())
                .startsWith("NICHT GEMESSEN: request_metrics (keine Messwerte")
                .contains("FAKT request_metrics_history_days: 1 (2026-09-27 bis 2026-09-27)");
    }

    /** A failing history read costs the history part, not the live numbers. */
    @Test
    void failingHistoryReadKeepsTheLiveFacts() {
        when(queryService.liveWindow()).thenReturn(List.of(route("GET /api/v1/todos", 300, 3, 0.1, 100)));
        when(watchdog.detectDrift()).thenReturn(List.of());
        when(queryService.recentDays(anyInt())).thenThrow(new IllegalStateException("pool timeout"));

        assertThat(factSheet.render())
                .contains("FAKT request_metrics_routes_measured: 1")
                .endsWith("NICHT GEMESSEN: request_metrics_history (Lesefehler: IllegalStateException)");
    }
}
