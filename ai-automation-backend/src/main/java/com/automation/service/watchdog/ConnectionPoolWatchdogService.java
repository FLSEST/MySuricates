package com.automation.service.watchdog;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.service.activitylog.ActivityLogService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Monitors the HikariCP connection pool for exhaustion (stuck connections).
 * With pool-size=1 (SQLite), a single held connection blocks ALL DB operations.
 *
 * Detection uses HikariPoolMXBean (in-memory counters, no DB connection needed).
 * Alerts via ActivityLog + WebSocket broadcast.
 */
@Service
@Slf4j
public class ConnectionPoolWatchdogService {

    private static final Duration EXHAUSTION_THRESHOLD = Duration.ofSeconds(60);
    private static final String SOURCE = "ConnectionPoolWatchdog";

    private final ActivityLogService activityLogService;
    private final SimpMessagingTemplate messagingTemplate;
    private final HikariDataSource hikariDataSource;

    private volatile Instant exhaustionDetectedAt;
    private volatile boolean alerted;
    private volatile String pendingLogMessage;
    private volatile Map<String, Object> pendingLogMetadata;

    public ConnectionPoolWatchdogService(DataSource dataSource,
                                          ActivityLogService activityLogService,
                                          SimpMessagingTemplate messagingTemplate) {
        this.activityLogService = activityLogService;
        this.messagingTemplate = messagingTemplate;
        this.hikariDataSource = unwrapHikariDataSource(dataSource);
    }

    @Scheduled(fixedRate = 15_000, initialDelay = 30_000)
    public void checkPoolHealth() {
        flushPendingLog();

        if (hikariDataSource == null) return;

        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) return;

        int active = pool.getActiveConnections();
        int waiting = pool.getThreadsAwaitingConnection();
        int idle = pool.getIdleConnections();
        int total = pool.getTotalConnections();

        if (active >= 1 && waiting > 0) {
            if (exhaustionDetectedAt == null) {
                exhaustionDetectedAt = Instant.now();
                log.warn("Pool exhaustion detected: active={}, waiting={}, idle={}, total={}",
                        active, waiting, idle, total);
            }

            Duration elapsed = Duration.between(exhaustionDetectedAt, Instant.now());
            if (elapsed.compareTo(EXHAUSTION_THRESHOLD) > 0 && !alerted) {
                handleExhaustion(active, waiting, idle, total, elapsed);
                alerted = true;
            }
        } else {
            if (exhaustionDetectedAt != null) {
                Duration elapsed = Duration.between(exhaustionDetectedAt, Instant.now());
                log.info("Pool exhaustion resolved after {}s", elapsed.toSeconds());

                if (alerted) {
                    tryLog("Pool exhaustion resolved after " + elapsed.toSeconds() + "s",
                            Map.of("elapsed_seconds", elapsed.toSeconds()), false);
                }

                exhaustionDetectedAt = null;
                alerted = false;
            }
        }
    }

    private void handleExhaustion(int active, int waiting, int idle, int total, Duration elapsed) {
        String message = String.format(
                "HikariPool connection exhaustion for %ds — active=%d, waiting=%d, idle=%d, total=%d. "
                + "A transaction is holding the single connection too long.",
                elapsed.toSeconds(), active, waiting, idle, total);
        log.error(message);

        broadcastAlert(message);

        Map<String, Object> metadata = Map.of(
                "active", active,
                "waiting", waiting,
                "idle", idle,
                "total", total,
                "elapsed_seconds", elapsed.toSeconds());

        tryLog(message, metadata, true);
    }

    private void tryLog(String message, Map<String, Object> metadata, boolean isError) {
        try {
            if (isError) {
                activityLogService.log(
                        com.automation.entity.ActivityLog.LogLevel.ERROR,
                        LogCategory.SYSTEM, message, SOURCE, null, metadata);
            } else {
                activityLogService.log(
                        com.automation.entity.ActivityLog.LogLevel.INFO,
                        LogCategory.SYSTEM, message, SOURCE, null, metadata);
            }
        } catch (Exception e) {
            log.debug("ActivityLog write failed (connection still exhausted), deferring: {}", e.getMessage());
            pendingLogMessage = message;
            pendingLogMetadata = metadata;
        }
    }

    private void flushPendingLog() {
        if (pendingLogMessage == null) return;
        try {
            activityLogService.error(LogCategory.SYSTEM, pendingLogMessage, SOURCE);
            log.info("Deferred ActivityLog entry written after pool recovery");
            pendingLogMessage = null;
            pendingLogMetadata = null;
        } catch (Exception e) {
            log.debug("Deferred ActivityLog write still failing, will retry next tick");
        }
    }

    private void broadcastAlert(String message) {
        try {
            messagingTemplate.convertAndSend("/topic/activity-log", Map.of(
                    "timestamp", Instant.now().toString(),
                    "level", "ERROR",
                    "category", "SYSTEM",
                    "message", message,
                    "source", SOURCE));
        } catch (Exception e) {
            log.debug("WebSocket broadcast failed: {}", e.getMessage());
        }
    }

    private static HikariDataSource unwrapHikariDataSource(DataSource dataSource) {
        try {
            if (dataSource instanceof HikariDataSource hds) return hds;
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class);
            }
        } catch (Exception e) {
            log.warn("Cannot unwrap HikariDataSource — ConnectionPoolWatchdog disabled: {}", e.getMessage());
        }
        return null;
    }
}
