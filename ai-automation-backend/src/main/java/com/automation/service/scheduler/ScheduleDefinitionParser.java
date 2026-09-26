package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow.ConflictBehavior;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

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

    /**
     * The three enum fields go through {@link EnumParameter} (TODO a9bf13d5): trimmed, in any case, and
     * an unknown value names the allowed ones. {@code toUpperCase()} without a locale turned
     * {@code "chain"} into {@code "CHAİN"} under a Turkish default locale.
     */
    public TargetType parseTargetType(String targetType) {
        TargetType parsed = EnumParameter.parseOptional(TargetType.class, "targetType", targetType);
        return parsed != null ? parsed : TargetType.WORKFLOW;
    }

    public ScheduleType parseScheduleType(String scheduleType) {
        ScheduleType parsed = EnumParameter.parseOptional(ScheduleType.class, "scheduleType", scheduleType);
        return parsed != null ? parsed : ScheduleType.CRON;
    }

    /**
     * {@code SKIP} or {@code OVERLAP}, trimmed, in any case. Strict on purpose — a request that names
     * an unknown value must fail instead of silently keeping the old (or the default) behaviour.
     */
    public ConflictBehavior parseConflictBehavior(String conflictBehavior) {
        return EnumParameter.parse(ConflictBehavior.class, "conflictBehavior", conflictBehavior);
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
