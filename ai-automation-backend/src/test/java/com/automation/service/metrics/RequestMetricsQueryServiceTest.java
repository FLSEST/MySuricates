package com.automation.service.metrics;

import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.repository.RequestMetricDailyRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestMetricsQueryServiceTest {

    private static final String ROUTE = "GET /api/v1/todos";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);
    private final RequestMetricsCollector collector = new RequestMetricsCollector(clock);
    private final RequestMetricDailyRepository repository = mock(RequestMetricDailyRepository.class);
    private final RequestMetricsQueryService service = new RequestMetricsQueryService(collector, repository, clock);

    @Test
    void liveWindowComesFromMemory() {
        collector.record(ROUTE, 10, 0, 1, 100);

        assertThat(service.liveWindow()).extracting(RequestMetricsCollector.RouteSnapshot::route)
                .containsExactly(ROUTE);
    }

    @Test
    void todayQueriesTheClocksDate() {
        RequestMetricDaily row = RequestMetricDaily.builder().route(ROUTE).day(TODAY).build();
        when(repository.findByDayOrderByP95MsDesc(TODAY)).thenReturn(List.of(row));

        assertThat(service.today()).containsExactly(row);
    }

    @Test
    void historyCoversTheRequestedDaysUpToToday() {
        service.history(ROUTE, 7);

        verify(repository).findByRouteAndDayBetweenOrderByDayAsc(ROUTE, TODAY.minusDays(7), TODAY);
    }

    @Test
    void historyIsCappedAtTheMaximum() {
        service.history(ROUTE, 10_000);

        verify(repository).findByRouteAndDayBetweenOrderByDayAsc(
                ROUTE, TODAY.minusDays(RequestMetricsQueryService.MAX_HISTORY_DAYS), TODAY);
    }

    @Test
    void historyOfZeroOrNegativeDaysStillCoversOneDay() {
        service.history(ROUTE, 0);
        service.history(ROUTE, -3);

        verify(repository, org.mockito.Mockito.times(2))
                .findByRouteAndDayBetweenOrderByDayAsc(ROUTE, TODAY.minusDays(1), TODAY);
    }

    /** One query for all routes of the window, today included — never a loop on the single connection. */
    @Test
    void recentDaysReadsAllRoutesUpToTodayInOneQuery() {
        RequestMetricDaily row = RequestMetricDaily.builder().route(ROUTE).day(TODAY).build();
        when(repository.findByDayBetweenOrderByDayAsc(TODAY.minusDays(30), TODAY)).thenReturn(List.of(row));

        assertThat(service.recentDays(30)).containsExactly(row);
    }

    @Test
    void recentDaysIsCappedAtTheMaximum() {
        service.recentDays(10_000);

        verify(repository).findByDayBetweenOrderByDayAsc(
                TODAY.minusDays(RequestMetricsQueryService.MAX_HISTORY_DAYS), TODAY);
    }

    @Test
    void liveWindowStartComesFromTheCollector() {
        assertThat(service.liveWindowOpenedAt()).isEqualTo(Instant.parse("2026-09-21T10:00:00Z"));
    }
}
