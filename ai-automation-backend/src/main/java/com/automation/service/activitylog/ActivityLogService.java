package com.automation.service.activitylog;

import com.automation.entity.ActivityLog;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    private final ActivityLogRepository repository;
    private final SimpMessagingTemplate messagingTemplate;

    public ActivityLog log(LogLevel level, LogCategory category, String message,
                           String source, String referenceId, Map<String, Object> metadata) {
        ActivityLog entry = ActivityLog.builder()
                .timestamp(Instant.now())
                .level(level)
                .category(category)
                .message(message)
                .source(source)
                .referenceId(referenceId)
                .metadata(metadata != null ? metadata : Map.of())
                .build();

        entry = repository.save(entry);

        try {
            messagingTemplate.convertAndSend("/topic/activity-log", Map.of(
                    "id", entry.getId(),
                    "timestamp", entry.getTimestamp().toString(),
                    "level", entry.getLevel().name(),
                    "category", entry.getCategory().name(),
                    "message", entry.getMessage()
            ));
        } catch (Exception e) {
            // Don't fail logging if WebSocket broadcast fails
        }

        return entry;
    }

    public ActivityLog info(LogCategory category, String message) {
        return log(LogLevel.INFO, category, message, null, null, null);
    }

    public ActivityLog info(LogCategory category, String message, String source) {
        return log(LogLevel.INFO, category, message, source, null, null);
    }

    public ActivityLog info(LogCategory category, String message, String source, String referenceId) {
        return log(LogLevel.INFO, category, message, source, referenceId, null);
    }

    public ActivityLog warn(LogCategory category, String message, String source) {
        return log(LogLevel.WARN, category, message, source, null, null);
    }

    public ActivityLog warn(LogCategory category, String message, String source, String referenceId) {
        return log(LogLevel.WARN, category, message, source, referenceId, null);
    }

    public ActivityLog error(LogCategory category, String message, String source) {
        return log(LogLevel.ERROR, category, message, source, null, null);
    }

    public ActivityLog error(LogCategory category, String message, String source, String referenceId) {
        return log(LogLevel.ERROR, category, message, source, referenceId, null);
    }

    public Page<ActivityLog> findAll(int page, int size, LogLevel level, LogCategory category) {
        PageRequest pageRequest = PageRequest.of(page, size);

        if (level != null && category != null) {
            return repository.findByLevelAndCategoryOrderByTimestampDesc(level, category, pageRequest);
        } else if (level != null) {
            return repository.findByLevelOrderByTimestampDesc(level, pageRequest);
        } else if (category != null) {
            return repository.findByCategoryOrderByTimestampDesc(category, pageRequest);
        }
        return repository.findAllByOrderByTimestampDesc(pageRequest);
    }

    /**
     * Tiered retention cleanup - runs daily at 03:00.
     *
     * - Delete everything older than 30 days
     * - Delete DEBUG + INFO older than 7 days
     * - Delete DEBUG older than 24 hours
     *
     * Result: Last 24h = all levels, 1-7d = INFO+, 7-30d = WARN+, >30d = nothing
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupLogs() {
        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        int deleted30d = repository.deleteOlderThan(thirtyDaysAgo);
        int deleted7d = repository.deleteByLevelsOlderThan(
                List.of(LogLevel.DEBUG, LogLevel.INFO), sevenDaysAgo);
        int deleted1d = repository.deleteByLevelsOlderThan(
                List.of(LogLevel.DEBUG), oneDayAgo);

        int total = deleted30d + deleted7d + deleted1d;
        if (total > 0) {
            log.info("Activity log cleanup: removed {} entries (30d: {}, 7d: {}, 1d: {})",
                    total, deleted30d, deleted7d, deleted1d);
        }
    }
}
