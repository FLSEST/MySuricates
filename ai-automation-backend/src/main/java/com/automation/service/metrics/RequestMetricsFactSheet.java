package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders the measured request costs in the audit protocol's fact format, so the performance audit
 * can state them instead of guessing at them.
 *
 * <p>The audit's contract is "the shell measures, the AI only interprets"
 * ({@code docs/audit-workflow-protocol.md}): every number is a {@code FAKT <name>: <value>} line
 * and every gap an explicit {@code NICHT GEMESSEN: <name> (<reason>)}. A gap has to be stated
 * rather than left out — an absent line would read to the AI as "fine".
 *
 * <p>Two parts: the live window since the last restart or snapshot — with its start, its call
 * count and the costliest routes by name — and the stored history ({@link RequestMetricsHistoryFacts}).
 * The live window alone can be thirty minutes old, which is what the audit of 2026-09-28 judged
 * (TODO f625c113). A route with fewer than {@code driftMinCalls} calls is counted, not ranked: its
 * p95 is one cold request.
 *
 * <p>Reaches the workflow as the {@code request_metrics} context variable, never through the REST
 * API: shell blocks are not allowed to call it (CLAUDE.md).
 */
@Service
@Slf4j
public class RequestMetricsFactSheet {

    static final int TOP = 5;

    private final RequestMetricsQueryService queryService;
    private final RequestMetricsDriftWatchdogService driftWatchdog;
    private final RequestMetricsConfig config;
    private final RequestMetricsHistoryFacts historyFacts;
    private final Clock clock;

    public RequestMetricsFactSheet(RequestMetricsQueryService queryService,
                                   RequestMetricsDriftWatchdogService driftWatchdog,
                                   RequestMetricsConfig config,
                                   Clock clock) {
        this.queryService = queryService;
        this.driftWatchdog = driftWatchdog;
        this.config = config;
        this.historyFacts = new RequestMetricsHistoryFacts(config);
        this.clock = clock;
    }

    public String render() {
        StringBuilder facts = new StringBuilder();
        renderLiveWindow(facts);
        renderHistory(facts);
        return facts.toString().stripTrailing();
    }

    private void renderLiveWindow(StringBuilder facts) {
        List<RouteSnapshot> routes = queryService.liveWindow();
        if (routes.isEmpty()) {
            facts.append("NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)\n");
            return;
        }

        RouteSnapshot slowest = routes.get(0); // snapshot() sorts by p95 descending
        RouteSnapshot mostStatements = routes.stream()
                .max((a, b) -> Integer.compare(a.maxSqlStatements(), b.maxSqlStatements())).orElse(slowest);
        RouteSnapshot mostWaiting = routes.stream()
                .max((a, b) -> Double.compare(a.waitShare(), b.waitShare())).orElse(slowest);

        fact(facts, "request_metrics_routes_measured", String.valueOf(routes.size()));
        fact(facts, "request_metrics_slowest_route", slowest.route());
        fact(facts, "request_metrics_slowest_p95_ms", String.valueOf(slowest.p95Ms()));
        fact(facts, "request_metrics_max_sql_route", mostStatements.route());
        fact(facts, "request_metrics_max_sql_statements", String.valueOf(mostStatements.maxSqlStatements()));
        fact(facts, "request_metrics_max_wait_share_percent",
                String.format(Locale.ROOT, "%.1f", mostWaiting.waitShare() * 100));
        fact(facts, "request_metrics_max_wait_share_route", mostWaiting.route());
        fact(facts, "request_metrics_max_response_bytes", String.valueOf(
                routes.stream().mapToLong(RouteSnapshot::maxResponseBytes).max().orElse(0L)));

        List<RouteDrift> drifting = driftWatchdog.detectDrift();
        fact(facts, "request_metrics_drifting_routes", String.valueOf(drifting.size()));
        if (drifting.isEmpty()) {
            fact(facts, "request_metrics_drift_detail", "keine");
        } else {
            fact(facts, "request_metrics_drift_detail", drifting.stream()
                    .map(drift -> String.format(Locale.ROOT, "%s x%.1f", drift.route(), drift.factor()))
                    .collect(Collectors.joining("; ")));
        }

        renderWindowScope(facts, routes);
    }

    /** How much the live window covers and which routes carry enough calls to be ranked at all. */
    private void renderWindowScope(StringBuilder facts, List<RouteSnapshot> routes) {
        Instant openedAt = queryService.liveWindowOpenedAt();
        long minutes = Math.max(0, ChronoUnit.MINUTES.between(openedAt, clock.instant()));
        int minCalls = config.getDriftMinCalls();
        fact(facts, "request_metrics_window_start", openedAt.truncatedTo(ChronoUnit.SECONDS) + " (" + minutes + " min)");
        fact(facts, "request_metrics_window_calls", String.valueOf(routes.stream().mapToLong(RouteSnapshot::calls).sum()));
        fact(facts, "request_metrics_min_calls", String.valueOf(minCalls));

        List<RouteSnapshot> ranked = routes.stream().filter(route -> route.calls() >= minCalls).toList();
        fact(facts, "request_metrics_routes_below_min_calls", String.valueOf(routes.size() - ranked.size()));
        if (ranked.isEmpty()) {
            facts.append("NICHT GEMESSEN: request_metrics_top (keine Route mit mindestens ")
                    .append(minCalls).append(" Aufrufen im Live-Fenster)\n");
            return;
        }

        List<RouteSnapshot> byP95 = ranked.stream()
                .sorted(Comparator.comparingLong(RouteSnapshot::p95Ms).reversed()).limit(TOP).toList();
        for (int i = 0; i < byP95.size(); i++) {
            fact(facts, "request_metrics_top_p95_" + (i + 1), describe(byP95.get(i)));
        }
        List<RouteSnapshot> byWait = ranked.stream().filter(route -> route.waitShare() > 0)
                .sorted(Comparator.comparingDouble(RouteSnapshot::waitShare).reversed()).limit(TOP).toList();
        for (int i = 0; i < byWait.size(); i++) {
            fact(facts, "request_metrics_top_wait_" + (i + 1), describe(byWait.get(i)));
        }
    }

    private static String describe(RouteSnapshot route) {
        return String.format(Locale.ROOT, "%s | p95 %d ms | p50 %d ms | %d Aufrufe | Wartezeit %.1f %% | SQL %.1f",
                route.route(), route.p95Ms(), route.p50Ms(), route.calls(), route.waitShare() * 100,
                route.avgSqlStatements());
    }

    /** A failing read costs the history part, never the live numbers above it. */
    private void renderHistory(StringBuilder facts) {
        try {
            List<String> lines = historyFacts.render(
                    queryService.recentDays(RequestMetricsHistoryFacts.HISTORY_DAYS), LocalDate.now(clock));
            lines.forEach(line -> facts.append(line).append('\n'));
        } catch (RuntimeException e) {
            log.warn("Request metrics history unavailable for the fact sheet: {}", e.getMessage());
            facts.append("NICHT GEMESSEN: request_metrics_history (Lesefehler: ")
                    .append(e.getClass().getSimpleName()).append(")\n");
        }
    }

    private static void fact(StringBuilder target, String name, String value) {
        target.append("FAKT ").append(name).append(": ").append(value).append('\n');
    }
}
