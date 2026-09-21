package com.automation.service.metrics;

import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RequestMetricsFactSheetTest {

    private final RequestMetricsQueryService queryService = mock(RequestMetricsQueryService.class);
    private final RequestMetricsDriftWatchdogService watchdog = mock(RequestMetricsDriftWatchdogService.class);
    private final RequestMetricsFactSheet factSheet = new RequestMetricsFactSheet(queryService, watchdog);

    private static RouteSnapshot route(String name, long p95, int maxSql, double waitShare, long maxBytes) {
        return new RouteSnapshot(name, 50, p95 / 2, p95, p95, 0, waitShare, maxSql, maxSql / 2d, maxBytes, maxBytes / 2);
    }

    /** A gap is stated, never left out — an absent line would read to the AI as "fine". */
    @Test
    void noMeasurementsIsStatedExplicitly() {
        when(queryService.liveWindow()).thenReturn(List.of());

        assertThat(factSheet.render()).isEqualTo(
                "NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)");
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
                FAKT request_metrics_max_response_bytes: 80000
                FAKT request_metrics_drifting_routes: 0
                FAKT request_metrics_drift_detail: keine""");
    }

    @Test
    void driftingRoutesAreListedWithTheirFactor() {
        when(queryService.liveWindow()).thenReturn(List.of(route("GET /api/v1/todos", 300, 3, 0.1, 100)));
        when(watchdog.detectDrift()).thenReturn(List.of(
                new RouteDrift("GET /api/v1/todos", 100, 300, 3, 3, 0.1, 50),
                new RouteDrift("GET /api/v1/runs", 40, 100, 3, 3, 0.1, 50)));

        String facts = factSheet.render();

        assertThat(facts).contains("FAKT request_metrics_drifting_routes: 2\n")
                .endsWith("FAKT request_metrics_drift_detail: GET /api/v1/todos x3.0; GET /api/v1/runs x2.5");
    }
}
