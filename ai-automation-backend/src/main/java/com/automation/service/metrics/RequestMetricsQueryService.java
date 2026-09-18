package com.automation.service.metrics;

import com.automation.entity.RequestMetricDaily;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Read side of the request metrics: the live in-memory window and the stored daily history.
 *
 * <p>Separate from {@link RequestMetricsSnapshotService}, which only writes — and separate from the
 * controller, which must not reach into a repository ({@code LayerRuleTest} forbids
 * {@code controller.* -> repository.*}).
 */
@Service
@RequiredArgsConstructor
public class RequestMetricsQueryService {

    /** Upper bound on how far the history may be queried back. */
    public static final int MAX_HISTORY_DAYS = 90;

    private final RequestMetricsCollector collector;
    private final RequestMetricDailyRepository repository;
    private final Clock clock;

    /** Routes measured since the last daily snapshot, most expensive (p95) first. */
    public List<RouteSnapshot> liveWindow() {
        return collector.snapshot();
    }

    /** Today's stored rows, most expensive first. */
    @Transactional(readOnly = true)
    public List<RequestMetricDaily> today() {
        return repository.findByDayOrderByP95MsDesc(LocalDate.now(clock));
    }

    /** History of one route, oldest first — the baseline a drift check compares against. */
    @Transactional(readOnly = true)
    public List<RequestMetricDaily> history(String route, int days) {
        int window = Math.max(1, Math.min(days, MAX_HISTORY_DAYS));
        LocalDate today = LocalDate.now(clock);
        return repository.findByRouteAndDayBetweenOrderByDayAsc(route, today.minusDays(window), today);
    }
}
