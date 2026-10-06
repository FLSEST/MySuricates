package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * An appointment in "Braucht dich" (TODO 259f4f66, c60bf914), with the goal the link leads to:
 * a milestone that is due soon or overdue ({@code kind = MILESTONE}; {@code total}/
 * {@code done} count its linked work packages), or an overdue work package with
 * open TODOs ({@code kind = WORK_PACKAGE}; {@code dueDate} is its end, {@code total}/{@code done}
 * count its TODOs).
 */
public record MilestoneAttentionItem(
        String id,
        String title,
        String goalId,
        String goalTitle,
        Instant dueDate,
        GoalDueState state,
        long total,
        long done,
        Kind kind
) {
    public enum Kind { MILESTONE, WORK_PACKAGE }
}
