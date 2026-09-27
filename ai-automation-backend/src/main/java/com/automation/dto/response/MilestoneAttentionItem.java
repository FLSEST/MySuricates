package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * Ein Termin in „Braucht dich“ (TODO 259f4f66, c60bf914), mit dem Ziel, zu dem der Link fuehrt:
 * ein Meilenstein, der bald faellig oder ueberfaellig ist ({@code kind = MILESTONE}; {@code total}/
 * {@code done} zaehlen seine verknuepften Arbeitspakete), oder ein ueberfaelliges Arbeitspaket mit
 * offenen TODOs ({@code kind = WORK_PACKAGE}; {@code dueDate} ist sein Ende, {@code total}/{@code done}
 * zaehlen seine TODOs).
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
