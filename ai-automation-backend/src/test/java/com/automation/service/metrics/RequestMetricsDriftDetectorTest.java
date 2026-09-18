package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsDriftDetectorTest {

    private static final String ROUTE = "GET /api/v1/todos";

    private RequestMetricsConfig config;
    private RequestMetricsDriftDetector detector;

    @BeforeEach
    void setUp() {
        config = new RequestMetricsConfig();
        detector = new RequestMetricsDriftDetector(config);
    }

    private RouteSnapshot todayWith(long p95Ms, long calls) {
        return todayWith(p95Ms, calls, 3.0d, 0.1d);
    }

    private RouteSnapshot todayWith(long p95Ms, long calls, double avgSql, double waitShare) {
        return new RouteSnapshot(ROUTE, calls, p95Ms / 2, p95Ms, p95Ms, (long) (p95Ms * waitShare),
                waitShare, (int) Math.ceil(avgSql), avgSql, 10_000, 5_000);
    }

    private RequestMetricDaily day(int daysAgo, long p95Ms, double avgSql) {
        return RequestMetricDaily.builder()
                .day(LocalDate.now().minusDays(daysAgo))
                .route(ROUTE)
                .calls(100)
                .p95Ms(p95Ms)
                .avgSqlStatements(avgSql)
                .build();
    }

    private List<RequestMetricDaily> steadyBaseline(long p95Ms) {
        return List.of(day(3, p95Ms, 3.0), day(2, p95Ms, 3.0), day(1, p95Ms, 3.0));
    }

    @Nested
    class Reports {

        @Test
        void aRouteThatDoubled() {
            List<RouteDrift> drift = detector.detect(
                    List.of(todayWith(900, 100)), steadyBaseline(120));

            assertThat(drift).hasSize(1);
            assertThat(drift.get(0).baselineP95Ms()).isEqualTo(120);
            assertThat(drift.get(0).currentP95Ms()).isEqualTo(900);
            assertThat(drift.get(0).factor()).isEqualTo(7.5);
        }

        @Test
        void worstOffenderFirst() {
            RouteSnapshot mild = new RouteSnapshot("GET /api/v1/a", 100, 100, 300, 300, 10, 0.1, 3, 3.0, 100, 100);
            RouteSnapshot severe = new RouteSnapshot("GET /api/v1/b", 100, 100, 2_000, 2_000, 10, 0.1, 3, 3.0, 100, 100);
            List<RequestMetricDaily> history = List.of(
                    RequestMetricDaily.builder().day(LocalDate.now().minusDays(1))
                            .route("GET /api/v1/a").p95Ms(100).avgSqlStatements(3.0).build(),
                    RequestMetricDaily.builder().day(LocalDate.now().minusDays(1))
                            .route("GET /api/v1/b").p95Ms(100).avgSqlStatements(3.0).build());

            List<RouteDrift> drift = detector.detect(List.of(mild, severe), history);

            assertThat(drift).extracting(RouteDrift::route)
                    .containsExactly("GET /api/v1/b", "GET /api/v1/a");
        }

        /** One bad day in the baseline must not raise the bar and hide the next regression. */
        @Test
        void medianBaselineIgnoresASingleBadDay() {
            List<RequestMetricDaily> history = List.of(
                    day(3, 100, 3.0), day(2, 5_000, 3.0), day(1, 100, 3.0));

            List<RouteDrift> drift = detector.detect(List.of(todayWith(400, 100)), history);

            assertThat(drift).hasSize(1);
            assertThat(drift.get(0).baselineP95Ms())
                    .as("the median ignores the outlier day, a mean would have been ~1733")
                    .isEqualTo(100);
        }
    }

    @Nested
    class StaysQuiet {

        @Test
        void whenTheRouteIsUnchanged() {
            assertThat(detector.detect(List.of(todayWith(130, 100)), steadyBaseline(120))).isEmpty();
        }

        @Test
        void whenTheRouteGotFaster() {
            assertThat(detector.detect(List.of(todayWith(40, 100)), steadyBaseline(120))).isEmpty();
        }

        /** The single most important non-alarm: a handful of cold requests after a restart. */
        @Test
        void whenTooFewCallsToJudge() {
            assertThat(detector.detect(List.of(todayWith(5_000, 3)), steadyBaseline(120)))
                    .as("3 calls is not evidence, however slow they were")
                    .isEmpty();
        }

        @Test
        void whenThereIsNoBaselineYet() {
            assertThat(detector.detect(List.of(todayWith(5_000, 100)), List.of())).isEmpty();
        }

        /** 1 ms against 3 ms is a factor of three and still means nothing. */
        @Test
        void whenTheBaselineIsTooSmallToBeMeaningful() {
            assertThat(detector.detect(List.of(todayWith(20, 100)), steadyBaseline(2))).isEmpty();
        }

        @Test
        void whenAnotherRouteDriftedButThisOneHasNoHistory() {
            RouteSnapshot unknown = new RouteSnapshot("GET /api/v1/new", 100, 10, 9_000, 9_000,
                    10, 0.1, 3, 3.0, 100, 100);

            assertThat(detector.detect(List.of(unknown), steadyBaseline(120))).isEmpty();
        }
    }

    @Nested
    class NamesTheLikelyCause {

        @Test
        void statementCountGrowingAlongsideTimeReadsAsAnNPlusOne() {
            List<RouteDrift> drift = detector.detect(
                    List.of(todayWith(900, 100, 47.0, 0.1)), steadyBaseline(120));

            assertThat(drift.get(0).statementsGrew()).isTrue();
            assertThat(drift.get(0).describe())
                    .contains("SQL 3.0 -> 47.0")
                    .contains("N+1");
        }

        @Test
        void mostlyWaitingPointsAwayFromTheRoute() {
            List<RouteDrift> drift = detector.detect(
                    List.of(todayWith(900, 100, 3.0, 0.9)), steadyBaseline(120));

            assertThat(drift.get(0).dominatedByWaiting()).isTrue();
            assertThat(drift.get(0).describe())
                    .contains("waiting for the DB connection")
                    .contains("elsewhere");
        }

        @Test
        void slowerWithoutMoreWorkNamesNoCause() {
            List<RouteDrift> drift = detector.detect(
                    List.of(todayWith(900, 100, 3.0, 0.1)), steadyBaseline(120));

            assertThat(drift.get(0).describe())
                    .contains("p95 120 -> 900 ms")
                    .doesNotContain("N+1")
                    .doesNotContain("elsewhere");
        }
    }

    @Nested
    class Configuration {

        @Test
        void factorIsHonoured() {
            config.setDriftFactor(10.0);

            assertThat(detector.detect(List.of(todayWith(900, 100)), steadyBaseline(120)))
                    .as("7.5x is below a factor of 10")
                    .isEmpty();
        }

        @Test
        void minimumCallsIsHonoured() {
            config.setDriftMinCalls(500);

            assertThat(detector.detect(List.of(todayWith(900, 100)), steadyBaseline(120))).isEmpty();
        }
    }
}
