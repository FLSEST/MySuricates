package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ConflictBehavior;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.util.EnumParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the user-facing schedule definition fields (target type, schedule type, conflict
 * behaviour, cron and delay expression, timing) and computes the next cron occurrence from the
 * injected clock.
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

    /** Refuses (400, translated) a cron expression that does not parse. */
    public void requireValidCron(String cronExpression) {
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new LocalizedArgumentException(ErrorMessage.INVALID_CRON, Map.of("expression", String.valueOf(cronExpression)));
        }
    }

    /** The timing fields of a new schedule by its type, with its first run. Moved from {@code SchedulerService}. */
    void applyTiming(ScheduledWorkflow.ScheduledWorkflowBuilder builder, ScheduleType type,
                     String cronExpression, Instant scheduledAt, String delayExpression) {
        switch (type) {
            case ONCE -> {
                if (scheduledAt == null) {
                    throw new IllegalArgumentException("scheduledAt is required for ONCE schedules");
                }
                builder.cronExpression("").scheduledAt(scheduledAt).nextRunAt(scheduledAt);
            }
            case DELAYED -> {
                if (delayExpression == null || delayExpression.isBlank()) {
                    throw new IllegalArgumentException("delayExpression is required for DELAYED schedules (e.g. '2h', '30m', '1d')");
                }
                Instant runAt = Instant.now(clock).plus(parseDelay(delayExpression));
                builder.cronExpression("").scheduledAt(runAt).nextRunAt(runAt).delayExpression(delayExpression);
            }
            default -> {
                if (cronExpression == null || cronExpression.isBlank()) {
                    throw new IllegalArgumentException("cronExpression is required for CRON schedules");
                }
                requireValidCron(cronExpression);
                builder.cronExpression(cronExpression).nextRunAt(calculateNextRun(cronExpression));
            }
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
        String trimmed = expr.trim().toLowerCase(Locale.ROOT);
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
