package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;
import java.util.List;

/**
 * Ein Meilenstein – ein Termin fuers ganze Ziel – mit berechnetem Zustand (TODO c60bf914).
 * Mit verknuepften Arbeitspaketen ({@code workPackageIds}) ist er REACHED, sobald alle abgeschlossen
 * sind ({@code packagesDone == packagesTotal}); ohne Pakete, wenn er von Hand markiert wurde.
 * {@code reopened}: war schon einmal erreicht ({@code reachedAt} gesetzt), die Pakete sind es jetzt
 * aber nicht mehr.
 */
public record GoalMilestoneResponse(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        GoalDueState state,
        List<String> workPackageIds,
        long packagesTotal,
        long packagesDone,
        Instant reachedAt,
        boolean reopened,
        Instant createdAt,
        Instant updatedAt
) {}
