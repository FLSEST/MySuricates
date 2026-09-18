package com.automation.integration;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.ActivityLog;
import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.repository.ActivityLogRepository;
import com.automation.repository.RequestMetricDailyRepository;
import com.automation.service.metrics.RequestMetricsDriftWatchdogService;
import com.automation.service.metrics.RouteDrift;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift watchdog against the real database: baseline rows are read back through the repository
 * and a drifting route reaches the ActivityLog exactly once a day.
 *
 * <p>Scheduling is off under the integration profile, so the check is invoked directly — the way
 * {@code docs/entwicklung.md} prescribes for every {@code @Scheduled} method.
 */
@SpringBootTest
@ActiveProfiles("integration")
class RequestMetricsDriftIntegrationTest {

    private static final String SOURCE = "RequestMetricsDriftWatchdog";

    private static final String ROUTE_PREFIX = "GET /api/v1/drift-probe/";

    /**
     * One route per test method. The watchdog remembers which routes it already reported today —
     * process-wide and by design, so an alert does not repeat every hour — and a shared route name
     * would let one test silence the next, depending on execution order.
     */
    private String route;

    @Autowired private RequestMetricsCollector collector;
    @Autowired private RequestMetricDailyRepository repository;
    @Autowired private RequestMetricsDriftWatchdogService watchdog;
    @Autowired private RequestMetricsConfig config;
    @Autowired private ActivityLogRepository activityLogRepository;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        route = ROUTE_PREFIX + testInfo.getTestMethod().orElseThrow().getName();
        collector.snapshotAndReset();
        config.setEnabled(true);
        config.setDriftDetectionEnabled(true);
        config.setDriftFactor(2.0);
        config.setDriftMinCalls(20);
        config.setBaselineDays(7);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        collector.snapshotAndReset();
        cleanUp();
    }

    private void cleanUp() {
        repository.deleteAll(repository.findAll().stream()
                .filter(row -> row.getRoute().startsWith(ROUTE_PREFIX))
                .toList());
        // The watchdog writes these itself, so without cleaning them a later test reads an
        // earlier test's alert and "stays quiet" fails for the wrong reason.
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(entry -> SOURCE.equals(entry.getSource()))
                .filter(entry -> entry.getMessage() != null && entry.getMessage().contains(ROUTE_PREFIX))
                .toList());
    }

    private void storeBaseline(long p95Ms, double avgSql) {
        for (int daysAgo = 1; daysAgo <= 3; daysAgo++) {
            repository.save(RequestMetricDaily.builder()
                    .day(LocalDate.now().minusDays(daysAgo))
                    .route(route)
                    .calls(100)
                    .p50Ms(p95Ms / 2)
                    .p95Ms(p95Ms)
                    .maxMs(p95Ms)
                    .p95WaitMs(5)
                    .waitShare(0.1)
                    .maxSqlStatements((int) Math.ceil(avgSql))
                    .avgSqlStatements(avgSql)
                    .maxResponseBytes(1_000)
                    .avgResponseBytes(1_000)
                    .createdAt(java.time.Instant.now())
                    .build());
        }
    }

    private void recordToday(long p95Ms, int sqlStatements, int calls) {
        for (int i = 0; i < calls; i++) {
            collector.record(route, p95Ms, 5, sqlStatements, 1_000);
        }
    }

    private List<ActivityLog> driftEntries() {
        return activityLogRepository.findAll().stream()
                .filter(entry -> SOURCE.equals(entry.getSource()))
                .filter(entry -> entry.getMessage() != null && entry.getMessage().contains(route))
                .toList();
    }

    @Test
    void readsTheBaselineFromTheDatabaseAndSpotsTheDrift() {
        storeBaseline(120, 3.0);
        recordToday(900, 47, 100);

        List<RouteDrift> drift = watchdog.detectDrift();

        assertThat(drift).extracting(RouteDrift::route).contains(route);
        RouteDrift probe = drift.stream().filter(d -> d.route().equals(route)).findFirst().orElseThrow();
        assertThat(probe.baselineP95Ms()).isEqualTo(120);
        assertThat(probe.currentP95Ms()).isEqualTo(900);
        assertThat(probe.statementsGrew()).isTrue();
    }

    @Test
    void writesTheAlertToTheActivityLogWithBothCosts() {
        storeBaseline(120, 3.0);
        recordToday(900, 47, 100);

        watchdog.checkForDrift();

        List<ActivityLog> entries = driftEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLevel()).isEqualTo(ActivityLog.LogLevel.WARN);
        assertThat(entries.get(0).getMessage())
                .contains("p95 120 -> 900 ms")
                .contains("SQL 3.0 -> 47.0")
                .contains("N+1");
    }

    /** An alert that repeats every hour is an alert people learn to ignore. */
    @Test
    void reportsEachRouteOnlyOncePerDay() {
        storeBaseline(120, 3.0);
        recordToday(900, 47, 100);

        watchdog.checkForDrift();
        recordToday(900, 47, 100);
        watchdog.checkForDrift();
        recordToday(900, 47, 100);
        watchdog.checkForDrift();

        assertThat(driftEntries()).hasSize(1);
    }

    @Test
    void staysQuietWhenTheRouteIsUnchanged() {
        storeBaseline(120, 3.0);
        recordToday(130, 3, 100);

        watchdog.checkForDrift();

        assertThat(driftEntries()).isEmpty();
    }

    @Test
    void staysQuietWithTooFewCallsToJudge() {
        storeBaseline(120, 3.0);
        recordToday(5_000, 47, 3);

        watchdog.checkForDrift();

        assertThat(driftEntries()).isEmpty();
    }

    @Test
    void staysQuietWithoutABaseline() {
        recordToday(5_000, 47, 100);

        watchdog.checkForDrift();

        assertThat(driftEntries()).isEmpty();
    }

    @Test
    void switchedOffMeansNoReport() {
        config.setDriftDetectionEnabled(false);
        storeBaseline(120, 3.0);
        recordToday(900, 47, 100);

        watchdog.checkForDrift();

        assertThat(driftEntries()).isEmpty();
    }
}
