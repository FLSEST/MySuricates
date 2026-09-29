package com.automation.metrics;

import com.automation.metrics.RequestMetricsCollector.DayWindow;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsCollectorTest {

    private RequestMetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new RequestMetricsCollector();
    }

    private RouteSnapshot only() {
        List<RouteSnapshot> snapshot = collector.snapshot();
        assertThat(snapshot).hasSize(1);
        return snapshot.get(0);
    }

    @Test
    void recordsCallsAndMaxima() {
        collector.record("GET /api/v1/todos", 10, 2, 3, 1_000);
        collector.record("GET /api/v1/todos", 50, 30, 47, 9_000);

        RouteSnapshot stats = only();
        assertThat(stats.route()).isEqualTo("GET /api/v1/todos");
        assertThat(stats.calls()).isEqualTo(2);
        assertThat(stats.maxMs()).isEqualTo(50);
        assertThat(stats.maxSqlStatements()).isEqualTo(47);
        assertThat(stats.maxResponseBytes()).isEqualTo(9_000);
        assertThat(stats.avgSqlStatements()).isEqualTo(25.0);
        assertThat(stats.avgResponseBytes()).isEqualTo(5_000);
    }

    /**
     * The reason for percentiles: one slow request in a hundred is the complaint, and a mean
     * would bury it. p95 has to see it while p50 stays calm.
     */
    @Test
    void percentilesExposeTheOutlierThatAnAverageWouldHide() {
        for (int i = 0; i < 99; i++) {
            collector.record("GET /api/v1/todos", 10, 0, 2, 500);
        }
        collector.record("GET /api/v1/todos", 5_000, 0, 2, 500);

        RouteSnapshot stats = only();
        assertThat(stats.p50Ms()).isEqualTo(10);
        assertThat(stats.p95Ms()).isEqualTo(10);
        assertThat(stats.maxMs()).isEqualTo(5_000);
    }

    @Test
    void p95FollowsTheSlowTail() {
        for (int i = 0; i < 90; i++) {
            collector.record("GET /api/v1/todos", 10, 0, 1, 100);
        }
        for (int i = 0; i < 10; i++) {
            collector.record("GET /api/v1/todos", 900, 0, 1, 100);
        }

        assertThat(only().p95Ms()).isEqualTo(900);
    }

    @Test
    void waitShareSeparatesQueuingFromWork() {
        collector.record("GET /api/v1/todos", 100, 80, 2, 100);
        collector.record("GET /api/v1/todos", 100, 80, 2, 100);

        assertThat(only().waitShare()).isEqualTo(0.8);
        assertThat(only().p95WaitMs()).isEqualTo(80);
    }

    @Test
    void routesAreKeptApart() {
        collector.record("GET /api/v1/todos", 10, 0, 2, 100);
        collector.record("GET /api/v1/workflows", 20, 0, 4, 200);

        assertThat(collector.snapshot()).extracting(RouteSnapshot::route)
                .containsExactlyInAnyOrder("GET /api/v1/todos", "GET /api/v1/workflows");
    }

    @Test
    void snapshotIsSortedByP95Descending() {
        collector.record("GET /api/v1/todos", 10, 0, 2, 100);
        collector.record("GET /api/v1/workflows", 900, 0, 4, 200);

        assertThat(collector.snapshot()).extracting(RouteSnapshot::route)
                .containsExactly("GET /api/v1/workflows", "GET /api/v1/todos");
    }

    @Test
    void drainStartsTheNextWindowEmpty() {
        collector.record("GET /api/v1/todos", 10, 0, 2, 100);

        assertThat(collector.drainWindows()).singleElement()
                .satisfies(window -> assertThat(window.routes()).hasSize(1));
        assertThat(collector.snapshot()).isEmpty();
        assertThat(collector.drainWindows()).isEmpty();
    }

    /**
     * A window belongs to the day it measured. The job that should have run at 23:55 caught up at
     * 01:04 after a standby and filed the 26th under the 27th (2026-09-27) — a day change closes
     * the running window under its own day instead.
     */
    @Test
    void dayChangeClosesTheWindowUnderTheDayItMeasured() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T21:00:00Z"));
        collector = new RequestMetricsCollector(clock);
        collector.record("GET /api/v1/todos", 10, 0, 1, 100);
        collector.record("GET /api/v1/todos", 20, 0, 1, 100);

        clock.set(Instant.parse("2026-09-27T01:04:00Z"));
        collector.record("GET /api/v1/todos", 30, 0, 1, 100);

        assertThat(collector.snapshot()).singleElement()
                .satisfies(route -> assertThat(route.calls()).isEqualTo(1));
        assertThat(collector.windowOpenedAt()).isEqualTo(Instant.parse("2026-09-27T00:00:00Z"));

        List<DayWindow> windows = collector.drainWindows();
        assertThat(windows).extracting(DayWindow::day)
                .containsExactly(LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27));
        assertThat(windows.get(0).routes().get(0).calls()).isEqualTo(2);
        assertThat(windows.get(1).routes().get(0).calls()).isEqualTo(1);
    }

    /** No request after midnight: the late job still files the window under the day it measured. */
    @Test
    void lateDrainWithoutNewRequestsKeepsTheMeasuredDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T21:00:00Z"));
        collector = new RequestMetricsCollector(clock);
        collector.record("GET /api/v1/todos", 10, 0, 1, 100);

        clock.set(Instant.parse("2026-09-27T01:04:00Z"));

        assertThat(collector.drainWindows()).extracting(DayWindow::day)
                .containsExactly(LocalDate.of(2026, 9, 26));
        assertThat(collector.windowOpenedAt()).isEqualTo(Instant.parse("2026-09-27T01:04:00Z"));
    }

    /** Days without a drain must not pile up: only the most recent closed windows are kept. */
    @Test
    void closedWindowsAreBounded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T12:00:00Z"));
        collector = new RequestMetricsCollector(clock);
        for (int day = 0; day < RequestMetricsCollector.MAX_CLOSED_WINDOWS + 3; day++) {
            clock.set(Instant.parse("2026-09-01T12:00:00Z").plus(Duration.ofDays(day)));
            collector.record("GET /api/v1/todos", 10, 0, 1, 100);
        }

        List<DayWindow> windows = collector.drainWindows();
        // The kept closed windows plus the running one; the oldest days are gone.
        assertThat(windows).hasSize(RequestMetricsCollector.MAX_CLOSED_WINDOWS + 1);
        assertThat(windows.get(windows.size() - 1).day()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(windows.get(0).day()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    /** Settable clock: the day change is what these tests are about. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An unparameterised path must not be able to grow the map without bound. */
    @Test
    void routeCardinalityIsCapped() {
        for (int i = 0; i < RequestMetricsCollector.MAX_ROUTES + 50; i++) {
            collector.record("GET /api/v1/thing/" + i, 10, 0, 1, 100);
        }

        assertThat(collector.snapshot()).hasSize(RequestMetricsCollector.MAX_ROUTES);
    }

    /** The window is bounded, so memory stays flat no matter how long the process runs. */
    @Test
    void durationWindowIsBoundedButCallCountIsNot() {
        for (int i = 0; i < RequestMetricsCollector.WINDOW + 500; i++) {
            collector.record("GET /api/v1/todos", 10, 0, 1, 100);
        }

        assertThat(only().calls()).isEqualTo(RequestMetricsCollector.WINDOW + 500);
        assertThat(only().p95Ms()).isEqualTo(10);
    }

    @Test
    void concurrentRecordingKeepsEveryCall() throws InterruptedException {
        int threads = 8;
        int perThread = 200;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        collector.record("GET /api/v1/todos", 10, 1, 2, 100);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(only().calls()).isEqualTo((long) threads * perThread);
    }
}
