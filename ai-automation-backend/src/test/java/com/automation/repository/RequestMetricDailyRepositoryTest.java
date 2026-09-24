package com.automation.repository;

import com.automation.entity.RequestMetricDaily;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RequestMetricDailyRepositoryTest {

    private static final String TODOS = "GET /api/v1/repo-test/todos";
    private static final String RUNS = "GET /api/v1/repo-test/runs";
    // Far in the past so rows written by other tests in this JVM cannot collide.
    private static final LocalDate DAY = LocalDate.of(2001, 3, 15);

    @Autowired private RequestMetricDailyRepository repository;

    private RequestMetricDaily save(LocalDate day, String route, long p95Ms) {
        return repository.save(RequestMetricDaily.builder()
                .day(day).route(route).calls(10).p50Ms(p95Ms / 2).p95Ms(p95Ms).maxMs(p95Ms)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void findsTheRowOfOneDayAndRoute() {
        RequestMetricDaily saved = save(DAY, TODOS, 80);
        save(DAY, RUNS, 40);
        save(DAY.minusDays(1), TODOS, 60);

        assertThat(repository.findByDayAndRoute(DAY, TODOS)).get()
                .extracting(RequestMetricDaily::getId).isEqualTo(saved.getId());
        assertThat(repository.findByDayAndRoute(DAY.plusDays(1), TODOS)).isEmpty();
    }

    @Test
    void rowsOfADayComeMostExpensiveFirst() {
        save(DAY, RUNS, 40);
        save(DAY, TODOS, 80);
        save(DAY.minusDays(1), "GET /api/v1/repo-test/other", 999);

        List<RequestMetricDaily> rows = repository.findByDayOrderByP95MsDesc(DAY);

        assertThat(rows).extracting(RequestMetricDaily::getRoute).containsExactly(TODOS, RUNS);
        assertThat(rows.get(0).getDay()).isEqualTo(DAY);
    }

    @Test
    void baselineWindowIsInclusiveAndChronological() {
        save(DAY.minusDays(8), TODOS, 1);
        save(DAY.minusDays(1), TODOS, 3);
        save(DAY.minusDays(7), TODOS, 2);
        save(DAY, TODOS, 4);
        save(DAY.minusDays(3), RUNS, 99);

        List<RequestMetricDaily> window = repository.findByRouteAndDayBetweenOrderByDayAsc(
                TODOS, DAY.minusDays(7), DAY.minusDays(1));

        assertThat(window).extracting(RequestMetricDaily::getP95Ms).containsExactly(2L, 3L);
    }

    /** The drift watchdog's single baseline query: every route, inclusive window, day order. */
    @Test
    void windowOverAllRoutesIsInclusiveAndChronological() {
        LocalDate far = LocalDate.of(1999, 6, 15); // own window, away from the other tests' rows
        save(far.minusDays(8), TODOS, 1);
        save(far.minusDays(1), TODOS, 4);
        save(far.minusDays(7), RUNS, 2);
        save(far.minusDays(3), TODOS, 3);
        save(far, RUNS, 5);

        List<RequestMetricDaily> window = repository.findByDayBetweenOrderByDayAsc(far.minusDays(7), far.minusDays(1));

        assertThat(window).extracting(RequestMetricDaily::getP95Ms).containsExactly(2L, 3L, 4L);
    }

    /** The day is stored as text: ordering must follow the calendar across a month boundary. */
    @Test
    void rangeQueryFollowsTheCalendarAcrossMonths() {
        save(LocalDate.of(2001, 9, 30), TODOS, 1);
        save(LocalDate.of(2001, 10, 1), TODOS, 2);
        save(LocalDate.of(2001, 10, 10), TODOS, 3);

        assertThat(repository.findByRouteAndDayBetweenOrderByDayAsc(
                TODOS, LocalDate.of(2001, 9, 30), LocalDate.of(2001, 10, 9)))
                .extracting(RequestMetricDaily::getP95Ms).containsExactly(1L, 2L);
    }

    @Test
    void deleteOlderThanKeepsTheCutoffDay() {
        save(DAY.minusDays(1), TODOS, 1);
        RequestMetricDaily onCutoff = save(DAY, TODOS, 2);
        RequestMetricDaily after = save(DAY.plusDays(1), TODOS, 3);

        int deleted = repository.deleteOlderThan(DAY);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(repository.findByRouteAndDayBetweenOrderByDayAsc(TODOS, DAY.minusDays(30), DAY.plusDays(30)))
                .extracting(RequestMetricDaily::getId).containsExactly(onCutoff.getId(), after.getId());
    }
}
