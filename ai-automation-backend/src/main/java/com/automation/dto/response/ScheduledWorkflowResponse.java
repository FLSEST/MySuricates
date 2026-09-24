package com.automation.dto.response;

import com.automation.entity.RunStatus;

import java.time.Instant;
import java.util.Map;

/**
 * A schedule as the views show it. {@code nextRunAt} is the next run the scheduler will actually
 * start: while an active skip-until pause holds the stored next run, it is the first occurrence
 * after the pause (null for a one-off, which expires), and {@code pausedUntil} names the pause's
 * end; otherwise {@code pausedUntil} is null and {@code nextRunAt} the stored value (TODO a776fd2a).
 */
public record ScheduledWorkflowResponse(
        String id,
        String workflowId,
        String workflowName,
        String projectName,
        String chainId,
        String chainName,
        String targetType,
        String cronExpression,
        String scheduleType,
        Instant scheduledAt,
        String delayExpression,
        boolean enabled,
        String description,
        Map<String, Object> inputContext,
        Instant lastRunAt,
        Instant nextRunAt,
        Instant createdAt,
        RunStatus lastRunStatus,
        Instant endDate,
        int maxRuns,
        int runCount,
        String conflictBehavior,
        boolean catchUpOnResume,
        Instant pausedUntil
) {}
