package com.automation.service.metrics;

import com.automation.config.RequestMetricsConfig;
import com.automation.entity.ActivityLog;
import com.automation.entity.RequestMetricDaily;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.metrics.RequestMetricsCollector;
import com.automation.metrics.RequestMetricsCollector.RouteSnapshot;
import com.automation.repository.RequestMetricDailyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notices when a route got slower than its own past and says so, once (TODO d86bed62).
 *
 * <p>This is the half of the measurement that does not wait to be asked. The ratchet catches a
 * regression before the commit; this catches what only shows up under real data and real
 * concurrency — the case where nobody would have looked until someone complained that the
 * platform "got slow again".
 *
 * <p>Modelled on {@code ConnectionPoolWatchdogService}: scheduled check, ActivityLog entry,
 * WebSocket broadcast. Reporting happens on this thread, never on a request thread — under
 * {@code maximum-pool-size: 1} a database write inside a slow request competes for the very
 * connection it is waiting for.
 *
 * <p>Each route is reported at most once per day. A drift that persists is the same news
 * tomorrow, and an alert that repeats hourly trains people to ignore it.
 */
@Service
@Slf4j
public class RequestMetricsDriftWatchdogService {

    private static final String SOURCE = "RequestMetricsDriftWatchdog";

    private final RequestMetricsCollector collector;
    private final RequestMetricDailyRepository repository;
    private final RequestMetricsConfig config;
    private final RequestMetricsDriftDetector detector;
    private final ActivityLogService activityLogService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final Clock clock;

    private final Set<String> reportedToday = new HashSet<>();
    private LocalDate reportedOn;

    public RequestMetricsDriftWatchdogService(RequestMetricsCollector collector,
                                              RequestMetricDailyRepository repository,
                                              RequestMetricsConfig config,
                                              ActivityLogService activityLogService,
                                              SimpMessagingTemplate messagingTemplate,
                                              PlatformTransactionManager transactionManager,
                                              Clock clock) {
        this.collector = collector;
        this.repository = repository;
        this.config = config;
        this.detector = new RequestMetricsDriftDetector(config);
        this.activityLogService = activityLogService;
        this.messagingTemplate = messagingTemplate;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.clock = clock;
    }

    /** Hourly: often enough to notice the day a deploy made something slower, rare enough to stay quiet. */
    @Scheduled(cron = "0 7 * * * *")
    public void checkForDrift() {
        if (!config.isEnabled() || !config.isDriftDetectionEnabled()) return;
        report(detectDrift());
    }

    /** The drifting routes right now — without reporting them. Used by the check and by tests. */
    public List<RouteDrift> detectDrift() {
        List<RouteSnapshot> today = collector.snapshot();
        if (today.isEmpty()) return List.of();

        LocalDate now = LocalDate.now(clock);
        List<RequestMetricDaily> history = readOnlyTransactionTemplate.execute(status ->
                loadBaseline(today, now));
        return detector.detect(today, history == null ? List.of() : history);
    }

    private List<RequestMetricDaily> loadBaseline(List<RouteSnapshot> today, LocalDate now) {
        LocalDate from = now.minusDays(config.getBaselineDays());
        LocalDate until = now.minusDays(1); // yesterday backwards: today is what we are judging
        // One query for the whole window instead of one per route: with pool-size 1 a loop over
        // every live route held the only connection for over a minute.
        Set<String> routes = new HashSet<>();
        for (RouteSnapshot snapshot : today) routes.add(snapshot.route());
        List<RequestMetricDaily> history = new ArrayList<>();
        for (RequestMetricDaily row : repository.findByDayBetweenOrderByDayAsc(from, until)) {
            if (routes.contains(row.getRoute())) history.add(row);
        }
        return history;
    }

    private void report(List<RouteDrift> drifting) {
        rolloverIfNewDay();
        for (RouteDrift drift : drifting) {
            if (!reportedToday.add(drift.route())) continue;

            String message = "Request cost drift — " + drift.describe();
            log.warn(message);
            broadcast(message);
            writeActivityLog(drift, message);
        }
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(reportedOn)) {
            reportedOn = today;
            reportedToday.clear();
        }
    }

    private void writeActivityLog(RouteDrift drift, String message) {
        Map<String, Object> metadata = Map.of(
                "route", drift.route(),
                "baseline_p95_ms", drift.baselineP95Ms(),
                "current_p95_ms", drift.currentP95Ms(),
                "factor", drift.factor(),
                "baseline_sql_statements", drift.baselineSqlStatements(),
                "current_sql_statements", drift.currentSqlStatements(),
                "wait_share", drift.currentWaitShare(),
                "calls", drift.calls());
        try {
            activityLogService.log(ActivityLog.LogLevel.WARN, ActivityLog.LogCategory.SYSTEM,
                    message, SOURCE, null, metadata);
        } catch (Exception e) {
            // The alert is already in the log and on the socket; losing the row must not stop the check.
            log.debug("ActivityLog write failed for drift on {}: {}", drift.route(), e.getMessage());
        }
    }

    private void broadcast(String message) {
        try {
            messagingTemplate.convertAndSend("/topic/activity-log",
                    (Object) Map.of("level", "WARN", "category", "SYSTEM", "message", message, "source", SOURCE));
        } catch (Exception e) {
            log.debug("WebSocket broadcast failed: {}", e.getMessage());
        }
    }
}
