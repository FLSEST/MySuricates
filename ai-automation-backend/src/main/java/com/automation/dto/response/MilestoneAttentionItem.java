package com.automation.dto.response;

import com.automation.entity.GoalMilestone;

import java.time.Instant;

/**
 * Ein Meilenstein in „Braucht dich“ (TODO 259f4f66): bald faellig oder ueberfaellig, mit dem Ziel,
 * zu dem der Link fuehrt.
 */
public record MilestoneAttentionItem(
        String id,
        String title,
        String goalId,
        String goalTitle,
        Instant dueDate,
        GoalMilestone.State state,
        long total,
        long done
) {}
