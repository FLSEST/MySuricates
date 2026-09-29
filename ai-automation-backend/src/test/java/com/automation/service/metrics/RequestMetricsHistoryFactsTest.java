package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsHistoryFactsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
    private static final String WORKFLOWS = "GET /api/v1/workflows";

    private final RequestMetricsConfig config = new RequestMetricsConfig();
    private final RequestMetricsHistoryFacts facts = new RequestMetricsHistoryFacts(config);

    private static RequestMetricDaily row(String day, String route, long calls, long p50, long p95,
                                          double avgSql, long avgBytes, double waitShare) {
        return RequestMetricDaily.builder().day(LocalDate.parse(day)).route(route).calls(calls)
                .p50Ms(p50).p95Ms(p95).maxMs(p95).waitShare(waitShare)
                .maxSqlStatements((int) Math.ceil(avgSql)).avgSqlStatements(avgSql)
                .maxResponseBytes(avgBytes).avgResponseBytes(avgBytes).build();
    }

    /** The workflow list as stored on the dev instance, 18.09.–27.09.2026 (no row for the 26th). */
    private static List<RequestMetricDaily> workflowListHistory() {
        return List.of(
                row("2026-09-18", WORKFLOWS, 15, 67, 137, 33, 1_620_208, 0.0),
                row("2026-09-19", WORKFLOWS, 59, 65, 335, 33, 1_643_316, 0.19),
                row("2026-09-20", WORKFLOWS, 4, 78, 111, 33, 1_781_853, 0.0),
                row("2026-09-21", WORKFLOWS, 18, 72, 648, 33, 1_829_156, 0.0),
                row("2026-09-22", WORKFLOWS, 63, 234, 911, 33, 1_833_411, 0.19),
                row("2026-09-23", WORKFLOWS, 50, 74, 325, 33, 1_852_755, 0.17),
                row("2026-09-24", WORKFLOWS, 6, 1050, 2012, 2, 1_876_933, 0.17),
                row("2026-09-25", WORKFLOWS, 5, 1215, 1827, 2, 1_911_608, 0.03),
                row("2026-09-27", WORKFLOWS, 10, 255, 475, 2, 1_928_989, 0.19));
    }

    private List<String> render(List<RequestMetricDaily> rows) {
        return facts.render(rows, TODAY);
    }

    @Test
    void noStoredDaysIsStatedExplicitly() {
        assertThat(render(List.of())).containsExactly(
                "NICHT GEMESSEN: request_metrics_history (keine gespeicherten Tageswerte der letzten 30 Tage)");
    }

    /** Gaps in the history are named — a day without a row is a restart or a machine that was off. */
    @Test
    void coverageNamesTheStoredRangeAndTheMissingDays() {
        List<String> lines = render(workflowListHistory());

        assertThat(lines).contains(
                "FAKT request_metrics_history_days: 9 (2026-09-18 bis 2026-09-27)",
                "FAKT request_metrics_history_missing_days: 2026-09-26",
                "FAKT request_metrics_history_min_daily_calls: 5");
    }

    /** Today is still open: its missing row is not a gap. */
    @Test
    void todayIsNeverAMissingDay() {
        List<String> lines = render(List.of(
                row("2026-09-26", WORKFLOWS, 10, 10, 20, 1, 100, 0),
                row("2026-09-27", WORKFLOWS, 10, 10, 20, 1, 100, 0)));

        assertThat(lines).contains("FAKT request_metrics_history_missing_days: keine");
    }

    /**
     * The case the audit of 2026-09-28 missed: slower over the last week, and the N+1 fix (33 → 2
     * statements) visible as a change in either direction. The 20th (4 calls) does not count.
     */
    @Test
    void workflowListTrendIsReported() {
        List<String> lines = render(workflowListHistory());

        assertThat(lines).contains(
                "FAKT request_metrics_trend_routes_compared: 1 (je Route mindestens 2 Tage in den letzten 7 und 3 davor, je Tag mindestens 5 Aufrufe)",
                "FAKT request_metrics_trend_slower_routes: 1",
                "FAKT request_metrics_trend_slower_1: GET /api/v1/workflows | p95 Median 911 ms (letzte 7 Tage, 5 Tage) "
                        + "gegen 335 ms (davor, 3 Tage), x2.7 | p50 255 gegen 67 ms | Wartezeit 17.0 % gegen 0.0 %",
                "FAKT request_metrics_trend_faster_routes: 0",
                // +14 %: below the 20 % threshold, so no finding yet — but close.
                "FAKT request_metrics_trend_response_growth_routes: 0",
                "FAKT request_metrics_trend_sql_change_routes: 1",
                "FAKT request_metrics_trend_sql_change_1: GET /api/v1/workflows | SQL Median 2.0 gegen 33.0 Statements je Aufruf");
    }

    /** Days with too few calls are one or two cold requests — they must not make a trend. */
    @Test
    void daysBelowTheMinimumDoNotMakeATrend() {
        List<RequestMetricDaily> rows = new ArrayList<>();
        for (int day = 10; day <= 27; day++) {
            rows.add(row("2026-09-" + day, WORKFLOWS, 4, 10, day < 22 ? 20 : 900, 1, 100, 0));
        }

        assertThat(render(rows)).contains("NICHT GEMESSEN: request_metrics_trend (keine vergleichbare Route: "
                + "je Route mindestens 2 Tage in den letzten 7 und 3 davor, je Tag mindestens 5 Aufrufe)");
    }

    @Test
    void growingResponsesAndFasterRoutesAreListed() {
        List<RequestMetricDaily> rows = new ArrayList<>();
        for (int day = 15; day <= 27; day++) {
            boolean recent = day >= 22;
            rows.add(row("2026-09-" + day, "GET /api/v1/executions", 30, 50, 100, 4, recent ? 300_000 : 200_000, 0));
            rows.add(row("2026-09-" + day, "GET /api/v1/projects", 30, 50, recent ? 60 : 180, 2, 5_000, 0));
        }

        assertThat(render(rows)).contains(
                "FAKT request_metrics_trend_response_growth_routes: 1",
                "FAKT request_metrics_trend_response_growth_1: GET /api/v1/executions | Antwort Median 300000 Bytes gegen 200000 Bytes (+50 %)",
                "FAKT request_metrics_trend_faster_routes: 1",
                "FAKT request_metrics_trend_faster_1: GET /api/v1/projects | p95 Median 60 ms (letzte 7 Tage, 6 Tage) "
                        + "gegen 180 ms (davor, 7 Tage), x0.3 | p50 50 gegen 50 ms | Wartezeit 0.0 % gegen 0.0 %");
    }

    /**
     * Drift judged day by day from the stored rows: the watchdog's alerts of 24.–26.09. only reached
     * the ActivityLog, the audit said "0 drifting routes". A quiet day (under 20 calls) is not drift.
     */
    @Test
    void driftDaysComeFromTheStoredRows() {
        String status = "PATCH /api/v1/todos/{id}/status";
        List<RequestMetricDaily> rows = new ArrayList<>();
        for (int day = 17; day <= 24; day++) {
            rows.add(row("2026-09-" + day, status, 43, 20, 40, 3.4, 200, 0.1));
        }
        rows.add(row("2026-09-25", status, 43, 120, 374, 3.5, 200, 0.65));
        rows.add(row("2026-09-26", "GET /api/v1/todos", 12, 10, 900, 5, 200, 0.1));
        for (int day = 17; day <= 25; day++) {
            rows.add(row("2026-09-" + day, "GET /api/v1/todos", 30, 20, 73, 5, 200, 0.1));
        }

        assertThat(render(rows)).contains(
                "FAKT request_metrics_drift_days_7d: 1 (Route-Tage mit p95 ueber dem 2.0-fachen Median der 7 Vortage, mindestens 20 Aufrufe)",
                "FAKT request_metrics_drift_day_1: 2026-09-25 PATCH /api/v1/todos/{id}/status | p95 374 ms gegen Median 40 ms (x9.4) | 43 Aufrufe | Wartezeit 65.0 %");
    }

    /** Only the last seven days are judged for drift; older drift is history, not news. */
    @Test
    void driftOlderThanTheRecentWindowIsNotListed() {
        List<RequestMetricDaily> rows = new ArrayList<>();
        for (int day = 5; day <= 12; day++) {
            rows.add(row("2026-09-" + String.format("%02d", day), WORKFLOWS, 43, 20, 40, 3, 200, 0.1));
        }
        rows.add(row("2026-09-13", WORKFLOWS, 43, 120, 400, 3, 200, 0.1));

        assertThat(render(rows)).anySatisfy(line -> assertThat(line).startsWith("FAKT request_metrics_drift_days_7d: 0 "))
                .noneSatisfy(line -> assertThat(line).startsWith("FAKT request_metrics_drift_day_1"));
    }
}
