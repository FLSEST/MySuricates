package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides which routes got slower than their own past. Pure comparison, no I/O — so the rule can
 * be tested for what it does and, just as importantly, for what it must not do: cry wolf.
 *
 * <p>A route is reported when all of the following hold:
 * <ul>
 *   <li>it has at least {@code driftMinCalls} calls today — otherwise one cold request after a
 *       restart, with the JIT not warm and every cache empty, reads as a catastrophe;</li>
 *   <li>a baseline exists at all, and that baseline is not itself near zero — comparing 1 ms
 *       against 3 ms is a factor of three and means nothing;</li>
 *   <li>today's p95 exceeds the baseline median by {@code driftFactor}.</li>
 * </ul>
 *
 * <p>The baseline is the <em>median</em> of the stored days, not their mean: one bad day (a big
 * import, a backup running) would drag a mean up and then hide the very regression that follows.
 */
public class RequestMetricsDriftDetector {

    /** Below this, percentage swings are noise — a few ms of scheduling jitter, not a regression. */
    static final long MIN_MEANINGFUL_BASELINE_MS = 25;

    private final RequestMetricsConfig config;

    public RequestMetricsDriftDetector(RequestMetricsConfig config) {
        this.config = config;
    }

    /**
     * @param today   the live window, one entry per route
     * @param history stored rows of the baseline window, any number of days per route
     */
    public List<RouteDrift> detect(List<RouteSnapshot> today, List<RequestMetricDaily> history) {
        Map<String, List<RequestMetricDaily>> baselineByRoute = history.stream()
                .collect(Collectors.groupingBy(RequestMetricDaily::getRoute));

        List<RouteDrift> drifting = new ArrayList<>();
        for (RouteSnapshot current : today) {
            if (current.calls() < config.getDriftMinCalls()) continue;

            List<RequestMetricDaily> baseline = baselineByRoute.get(current.route());
            if (baseline == null || baseline.isEmpty()) continue;

            long baselineP95 = Math.round(median(baseline, row -> (double) row.getP95Ms()));
            if (baselineP95 < MIN_MEANINGFUL_BASELINE_MS) continue;

            if (current.p95Ms() > baselineP95 * config.getDriftFactor()) {
                drifting.add(new RouteDrift(
                        current.route(),
                        baselineP95,
                        current.p95Ms(),
                        median(baseline, RequestMetricDaily::getAvgSqlStatements),
                        current.avgSqlStatements(),
                        current.waitShare(),
                        current.calls()));
            }
        }
        drifting.sort((a, b) -> Double.compare(b.factor(), a.factor()));
        return drifting;
    }

    /** Median, not mean: one bad day must not raise the bar that the next regression is judged by. */
    private static double median(List<RequestMetricDaily> rows, Function<RequestMetricDaily, Double> value) {
        List<Double> sorted = rows.stream().map(value).sorted().toList();
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2d;
    }
}
