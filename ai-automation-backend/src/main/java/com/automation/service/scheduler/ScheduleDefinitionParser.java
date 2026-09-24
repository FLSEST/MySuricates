package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow.ConflictBehavior;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Parses the user-facing schedule definition fields (target type, schedule type, conflict
 * behaviour, delay expression) and computes the next cron occurrence from the injected clock.
 *
 * <p>Extracted from {@code SchedulerService} (Audit-TODO 05dbd0e9). No persistence.
 */
@Service
@RequiredArgsConstructor
public class ScheduleDefinitionParser {

    private final Clock clock;

    public TargetType parseTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return TargetType.WORKFLOW;
        }
        try {
            return TargetType.valueOf(targetType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid target type: " + targetType);
        }
    }

    public ScheduleType parseScheduleType(String scheduleType) {
        if (scheduleType == null || scheduleType.isBlank()) {
            return ScheduleType.CRON;
        }
        try {
            return ScheduleType.valueOf(scheduleType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid schedule type: " + scheduleType);
        }
    }

    /**
     * {@code SKIP} or {@code OVERLAP}, case-insensitive. Strict on purpose — an update that names an
     * unknown value must fail instead of silently keeping the old behaviour.
     */
    public ConflictBehavior parseConflictBehavior(String conflictBehavior) {
        try {
            return ConflictBehavior.valueOf(conflictBehavior.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid conflict behavior: " + conflictBehavior
                    + " (expected SKIP or OVERLAP)");
        }
    }

    public Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Parses a human-readable delay expression into a {@link Duration}.
     * Supported formats: {@code 30s} (seconds), {@code 10m} (minutes), {@code 2h} (hours), {@code 1d} (days).
     */
    static Duration parseDelay(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Delay expression must not be empty");
        }
        String trimmed = expr.trim().toLowerCase();
        try {
            if (trimmed.endsWith("s")) return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("m")) return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("h")) return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("d")) return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid delay expression: '" + expr + "'. Expected format: 30s, 10m, 2h, 1d");
        }
        throw new IllegalArgumentException("Invalid delay expression: '" + expr + "'. Supported suffixes: s, m, h, d");
    }
}
