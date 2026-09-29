package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * The stored daily history in the audit's fact format: how much of it exists, which routes got
 * slower, heavier or chattier over the weeks, and which days drifted (TODO f625c113).
 *
 * <p>The live window alone told the audit of 2026-09-28 about thirty minutes after a restart. The
 * history held what mattered — the workflow list at one second p50 for two days, its response
 * growing from 1.6 to 1.9 MB, the N+1 fix taking it from 33 statements to 2 — and none of it
 * reached the report.
 *
 * <p>Pure — rows in, lines out — so every rule can be tested without a database. Medians, not
 * means, for the same reason as the drift detector: one bad day must not decide the trend.
 */
final class RequestMetricsHistoryFacts {

    static final int HISTORY_DAYS = 30;
    /** "Recent" is the last seven days including today; "earlier" is everything before. */
    static final int RECENT_DAYS = 7;
    static final int MIN_RECENT_DAYS = 2;
    static final int MIN_EARLIER_DAYS = 3;
    static final int TOP = 5;
    static final int MAX_DRIFT_DAYS_LISTED = 10;
    static final double SLOWER_FACTOR = 1.5;
    static final double RESPONSE_GROWTH_FACTOR = 1.2;
    static final long MIN_RESPONSE_BYTES = 100_000;
    static final double SQL_CHANGE_FACTOR = 1.5;
    static final double MIN_SQL_STATEMENTS = 3;

    private final RequestMetricsConfig config;
    private final RequestMetricsDriftDetector detector;

    RequestMetricsHistoryFacts(RequestMetricsConfig config) {
        this.config = config;
        this.detector = new RequestMetricsDriftDetector(config);
    }

    /** @param rows stored rows of the last {@link #HISTORY_DAYS} days up to today, any order */
    List<String> render(List<RequestMetricDaily> rows, LocalDate today) {
        List<String> lines = new ArrayList<>();
        TreeSet<LocalDate> days = rows.stream().map(RequestMetricDaily::getDay)
                .collect(Collectors.toCollection(TreeSet::new));
        if (days.isEmpty()) {
            lines.add("NICHT GEMESSEN: request_metrics_history (keine gespeicherten Tageswerte der letzten "
                    + HISTORY_DAYS + " Tage)");
            return lines;
        }

        lines.add(fact("request_metrics_history_days",
                days.size() + " (" + days.first() + " bis " + days.last() + ")"));
        lines.add(fact("request_metrics_history_missing_days", missingDays(days, today)));
        lines.add(fact("request_metrics_history_min_daily_calls", String.valueOf(config.getTrendMinDailyCalls())));
        renderTrend(rows, today, lines);
        renderDriftDays(rows, today, lines);
        return lines;
    }

    /** Days between the first stored day and yesterday without a single row — today is still open. */
    private static String missingDays(TreeSet<LocalDate> days, LocalDate today) {
        List<String> missing = new ArrayList<>();
        for (LocalDate day = days.first(); day.isBefore(today); day = day.plusDays(1)) {
            if (!days.contains(day)) missing.add(day.toString());
        }
        return missing.isEmpty() ? "keine" : String.join(", ", missing);
    }

    private void renderTrend(List<RequestMetricDaily> rows, LocalDate today, List<String> lines) {
        LocalDate recentFrom = today.minusDays(RECENT_DAYS - 1L);
        Map<String, List<RequestMetricDaily>> byRoute = rows.stream()
                .filter(row -> row.getCalls() >= config.getTrendMinDailyCalls())
                .collect(Collectors.groupingBy(RequestMetricDaily::getRoute, TreeMap::new, Collectors.toList()));

        List<RouteTrend> trends = new ArrayList<>();
        byRoute.forEach((route, routeRows) -> {
            List<RequestMetricDaily> recent = routeRows.stream().filter(row -> !row.getDay().isBefore(recentFrom)).toList();
            List<RequestMetricDaily> earlier = routeRows.stream().filter(row -> row.getDay().isBefore(recentFrom)).toList();
            if (recent.size() >= MIN_RECENT_DAYS && earlier.size() >= MIN_EARLIER_DAYS) {
                trends.add(new RouteTrend(route, recent, earlier));
            }
        });

        String rule = "je Route mindestens " + MIN_RECENT_DAYS + " Tage in den letzten " + RECENT_DAYS
                + " und " + MIN_EARLIER_DAYS + " davor, je Tag mindestens " + config.getTrendMinDailyCalls() + " Aufrufe";
        if (trends.isEmpty()) {
            lines.add("NICHT GEMESSEN: request_metrics_trend (keine vergleichbare Route: " + rule + ")");
            return;
        }
        lines.add(fact("request_metrics_trend_routes_compared", trends.size() + " (" + rule + ")"));

        List<RouteTrend> slower = trends.stream()
                .filter(trend -> trend.earlierP95() >= RequestMetricsDriftDetector.MIN_MEANINGFUL_BASELINE_MS)
                .filter(trend -> trend.p95Factor() >= SLOWER_FACTOR)
                .sorted(Comparator.comparingDouble(RouteTrend::p95Factor).reversed())
                .toList();
        list(lines, "request_metrics_trend_slower", slower, RouteTrend::describeTime);

        List<RouteTrend> faster = trends.stream()
                .filter(trend -> trend.earlierP95() >= RequestMetricsDriftDetector.MIN_MEANINGFUL_BASELINE_MS)
                .filter(trend -> trend.p95Factor() > 0 && trend.p95Factor() <= 1 / SLOWER_FACTOR)
                .sorted(Comparator.comparingDouble(RouteTrend::p95Factor))
                .toList();
        list(lines, "request_metrics_trend_faster", faster, RouteTrend::describeTime);

        List<RouteTrend> heavier = trends.stream()
                .filter(trend -> trend.recentBytes() >= MIN_RESPONSE_BYTES)
                .filter(trend -> trend.bytesFactor() >= RESPONSE_GROWTH_FACTOR)
                .sorted(Comparator.comparingDouble(RouteTrend::bytesFactor).reversed())
                .toList();
        list(lines, "request_metrics_trend_response_growth", heavier, RouteTrend::describeBytes);

        List<RouteTrend> chattier = trends.stream()
                .filter(trend -> Math.max(trend.recentSql(), trend.earlierSql()) >= MIN_SQL_STATEMENTS)
                .filter(trend -> trend.sqlChange() >= SQL_CHANGE_FACTOR)
                .sorted(Comparator.comparingDouble(RouteTrend::sqlChange).reversed())
                .toList();
        list(lines, "request_metrics_trend_sql_change", chattier, RouteTrend::describeSql);
    }

    /**
     * Drift judged day by day from the stored rows — the same rule the hourly watchdog applies to
     * the live window, so an alert that only reached the ActivityLog still reaches the audit.
     */
    private void renderDriftDays(List<RequestMetricDaily> rows, LocalDate today, List<String> lines) {
        Map<LocalDate, List<RequestMetricDaily>> byDay = rows.stream()
                .collect(Collectors.groupingBy(RequestMetricDaily::getDay));
        List<DayDrift> drifts = new ArrayList<>();
        for (int back = RECENT_DAYS - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            List<RequestMetricDaily> dayRows = byDay.get(day);
            if (dayRows == null) continue;
            LocalDate baselineFrom = day.minusDays(config.getBaselineDays());
            List<RequestMetricDaily> baseline = rows.stream()
                    .filter(row -> !row.getDay().isBefore(baselineFrom) && row.getDay().isBefore(day))
                    .toList();
            List<RouteSnapshot> asSnapshots = dayRows.stream().map(RequestMetricsHistoryFacts::toSnapshot).toList();
            for (RouteDrift drift : detector.detect(asSnapshots, baseline)) {
                drifts.add(new DayDrift(day, drift));
            }
        }
        drifts.sort(Comparator.comparingDouble((DayDrift drift) -> drift.drift().factor()).reversed());

        lines.add(fact("request_metrics_drift_days_" + RECENT_DAYS + "d", drifts.size()
                + " (Route-Tage mit p95 ueber dem " + fmt(config.getDriftFactor()) + "-fachen Median der "
                + config.getBaselineDays() + " Vortage, mindestens " + config.getDriftMinCalls() + " Aufrufe)"));
        for (int i = 0; i < Math.min(drifts.size(), MAX_DRIFT_DAYS_LISTED); i++) {
            DayDrift entry = drifts.get(i);
            RouteDrift drift = entry.drift();
            lines.add(fact("request_metrics_drift_day_" + (i + 1), String.format(Locale.ROOT,
                    "%s %s | p95 %d ms gegen Median %d ms (x%.1f) | %d Aufrufe | Wartezeit %.1f %%",
                    entry.day(), drift.route(), drift.currentP95Ms(), drift.baselineP95Ms(), drift.factor(),
                    drift.calls(), drift.currentWaitShare() * 100)));
        }
    }

    private static RouteSnapshot toSnapshot(RequestMetricDaily row) {
        return new RouteSnapshot(row.getRoute(), row.getCalls(), row.getP50Ms(), row.getP95Ms(), row.getMaxMs(),
                row.getP95WaitMs(), row.getWaitShare(), row.getMaxSqlStatements(), row.getAvgSqlStatements(),
                row.getMaxResponseBytes(), row.getAvgResponseBytes());
    }

    private static void list(List<String> lines, String name, List<RouteTrend> trends,
                             Function<RouteTrend, String> describe) {
        lines.add(fact(name + "_routes", String.valueOf(trends.size())));
        for (int i = 0; i < Math.min(trends.size(), TOP); i++) {
            lines.add(fact(name + "_" + (i + 1), describe.apply(trends.get(i))));
        }
    }

    private static String fact(String name, String value) {
        return "FAKT " + name + ": " + value;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static double median(List<RequestMetricDaily> rows, ToDoubleFunction<RequestMetricDaily> value) {
        double[] sorted = rows.stream().mapToDouble(value).sorted().toArray();
        int size = sorted.length;
        if (size == 0) return 0d;
        return size % 2 == 1 ? sorted[size / 2] : (sorted[size / 2 - 1] + sorted[size / 2]) / 2d;
    }

    private record DayDrift(LocalDate day, RouteDrift drift) {}

    /** One route, its recent days against its earlier days. */
    private record RouteTrend(String route, List<RequestMetricDaily> recent, List<RequestMetricDaily> earlier) {

        long recentP95() { return Math.round(median(recent, RequestMetricDaily::getP95Ms)); }
        long earlierP95() { return Math.round(median(earlier, RequestMetricDaily::getP95Ms)); }
        long recentP50() { return Math.round(median(recent, RequestMetricDaily::getP50Ms)); }
        long earlierP50() { return Math.round(median(earlier, RequestMetricDaily::getP50Ms)); }
        long recentBytes() { return Math.round(median(recent, RequestMetricDaily::getAvgResponseBytes)); }
        long earlierBytes() { return Math.round(median(earlier, RequestMetricDaily::getAvgResponseBytes)); }
        double recentSql() { return median(recent, RequestMetricDaily::getAvgSqlStatements); }
        double earlierSql() { return median(earlier, RequestMetricDaily::getAvgSqlStatements); }

        double p95Factor() {
            return earlierP95() <= 0 ? 0d : (double) recentP95() / earlierP95();
        }

        double bytesFactor() {
            return earlierBytes() <= 0 ? 0d : (double) recentBytes() / earlierBytes();
        }

        /** Change in either direction: an N+1 getting in and an N+1 getting fixed are both news. */
        double sqlChange() {
            double low = Math.min(recentSql(), earlierSql());
            double high = Math.max(recentSql(), earlierSql());
            return low <= 0 ? (high > 0 ? Double.MAX_VALUE : 1d) : high / low;
        }

        String describeTime() {
            return String.format(Locale.ROOT,
                    "%s | p95 Median %d ms (letzte %d Tage, %d Tage) gegen %d ms (davor, %d Tage), x%.1f | p50 %d gegen %d ms | Wartezeit %.1f %% gegen %.1f %%",
                    route, recentP95(), RECENT_DAYS, recent.size(), earlierP95(), earlier.size(), p95Factor(),
                    recentP50(), earlierP50(),
                    median(recent, RequestMetricDaily::getWaitShare) * 100,
                    median(earlier, RequestMetricDaily::getWaitShare) * 100);
        }

        String describeBytes() {
            return String.format(Locale.ROOT, "%s | Antwort Median %d Bytes gegen %d Bytes (%+.0f %%)",
                    route, recentBytes(), earlierBytes(), (bytesFactor() - 1) * 100);
        }

        String describeSql() {
            return String.format(Locale.ROOT, "%s | SQL Median %.1f gegen %.1f Statements je Aufruf",
                    route, recentSql(), earlierSql());
        }
    }
}
