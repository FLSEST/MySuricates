package com.automation.service.metrics;

import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
 * <p>Reaches the workflow as the {@code request_metrics} context variable, never through the REST
 * API: shell blocks are not allowed to call it (CLAUDE.md).
 */
@Service
@RequiredArgsConstructor
public class RequestMetricsFactSheet {

    private final RequestMetricsQueryService queryService;
    private final RequestMetricsDriftWatchdogService driftWatchdog;

    public String render() {
        List<RouteSnapshot> routes = queryService.liveWindow();
        if (routes.isEmpty()) {
            return "NICHT GEMESSEN: request_metrics (keine Messwerte seit dem letzten Tagesabschluss)";
        }

        RouteSnapshot slowest = routes.get(0); // snapshot() sorts by p95 descending
        RouteSnapshot mostStatements = routes.stream()
                .max((a, b) -> Integer.compare(a.maxSqlStatements(), b.maxSqlStatements())).orElse(slowest);
        RouteSnapshot mostWaiting = routes.stream()
                .max((a, b) -> Double.compare(a.waitShare(), b.waitShare())).orElse(slowest);

        StringBuilder facts = new StringBuilder();
        fact(facts, "request_metrics_routes_measured", String.valueOf(routes.size()));
        fact(facts, "request_metrics_slowest_route", slowest.route());
        fact(facts, "request_metrics_slowest_p95_ms", String.valueOf(slowest.p95Ms()));
        fact(facts, "request_metrics_max_sql_route", mostStatements.route());
        fact(facts, "request_metrics_max_sql_statements", String.valueOf(mostStatements.maxSqlStatements()));
        fact(facts, "request_metrics_max_wait_share_percent",
                String.format(Locale.ROOT, "%.1f", mostWaiting.waitShare() * 100));
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
        return facts.toString().stripTrailing();
    }

    private static void fact(StringBuilder target, String name, String value) {
        target.append("FAKT ").append(name).append(": ").append(value).append('\n');
    }
}
