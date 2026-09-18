package com.automation.service.metrics;

import java.util.Locale;

/**
 * One route that got slower than its own past.
 *
 * <p>Carries both costs, because the pair is what names the cause: time alone says "something is
 * slow", time together with a jump in statement count says "an N+1 got in" — the exact regression
 * that hit {@code /workflows} in {@code 1e465a9e}. A rise in wait share instead points away from
 * the route: something else is holding the single connection.
 */
public record RouteDrift(
        String route,
        long baselineP95Ms,
        long currentP95Ms,
        double baselineSqlStatements,
        double currentSqlStatements,
        double currentWaitShare,
        long calls) {

    /** How many times slower than its baseline the route is now. */
    public double factor() {
        return baselineP95Ms <= 0 ? 0d : (double) currentP95Ms / baselineP95Ms;
    }

    /** True when the statement count grew along with the time — the signature of an N+1. */
    public boolean statementsGrew() {
        return currentSqlStatements > baselineSqlStatements * 1.5d;
    }

    /** True when most of the time is spent queuing for the connection, not working. */
    public boolean dominatedByWaiting() {
        return currentWaitShare >= 0.5d;
    }

    /**
     * One line naming both costs and, where the numbers allow it, the likely cause — so the
     * ActivityLog entry is a lead, not just an alarm.
     */
    public String describe() {
        // Locale.ROOT throughout: this text goes into logs and the ActivityLog, where a decimal
        // comma on one machine and a point on another makes the same message unsearchable.
        String base = String.format(Locale.ROOT, "%s p95 %d -> %d ms (x%.1f), SQL %.1f -> %.1f, %d calls",
                route, baselineP95Ms, currentP95Ms, factor(),
                baselineSqlStatements, currentSqlStatements, calls);
        if (statementsGrew()) {
            return base + " — the statement count grew with it, which is what an N+1 looks like";
        }
        if (dominatedByWaiting()) {
            return base + String.format(Locale.ROOT,
                    " — %.0f%% of that is waiting for the DB connection, so the cause is "
                            + "more likely elsewhere than in this route", currentWaitShare * 100);
        }
        return base;
    }
}
