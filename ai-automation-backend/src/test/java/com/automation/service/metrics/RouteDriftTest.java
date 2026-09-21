package com.automation.service.metrics;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDriftTest {

    private static RouteDrift drift(long baseline, long current, double baseSql, double curSql, double wait) {
        return new RouteDrift("GET /api/v1/todos", baseline, current, baseSql, curSql, wait, 120);
    }

    @Test
    void factorIsCurrentOverBaseline() {
        assertThat(drift(100, 250, 3, 3, 0).factor()).isEqualTo(2.5d);
    }

    @Test
    void factorIsZeroWithoutAUsableBaseline() {
        assertThat(drift(0, 250, 3, 3, 0).factor()).isZero();
        assertThat(drift(-5, 250, 3, 3, 0).factor()).isZero();
    }

    @Test
    void statementsGrewOnlyAboveOneAndAHalfTimes() {
        assertThat(drift(100, 300, 4, 6, 0).statementsGrew()).isFalse(); // exactly 1.5x
        assertThat(drift(100, 300, 4, 6.1, 0).statementsGrew()).isTrue();
    }

    @Test
    void waitingDominatesFromHalfTheTime() {
        assertThat(drift(100, 300, 3, 3, 0.49).dominatedByWaiting()).isFalse();
        assertThat(drift(100, 300, 3, 3, 0.5).dominatedByWaiting()).isTrue();
    }

    @Test
    void describeNamesBothCostsWithoutACauseWhenNoneIsVisible() {
        assertThat(drift(100, 250, 3, 3.5, 0.1).describe())
                .isEqualTo("GET /api/v1/todos p95 100 -> 250 ms (x2.5), SQL 3.0 -> 3.5, 120 calls");
    }

    @Test
    void describeNamesAnNPlusOneWhenStatementsGrew() {
        assertThat(drift(100, 400, 3, 33, 0.9).describe())
                .endsWith("the statement count grew with it, which is what an N+1 looks like")
                .doesNotContain("waiting for the DB connection");
    }

    @Test
    void describePointsElsewhereWhenWaitingDominates() {
        assertThat(drift(100, 400, 3, 3, 0.8).describe())
                .contains("80% of that is waiting for the DB connection");
    }

    /** The text is searched in logs: a decimal comma on a German host would break that. */
    @Test
    void describeIgnoresTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(drift(100, 250, 3, 3.5, 0.1).describe()).contains("(x2.5)").contains("SQL 3.0 -> 3.5");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
