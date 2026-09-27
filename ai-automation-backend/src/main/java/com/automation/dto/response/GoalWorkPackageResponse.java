package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * Ein Arbeitspaket mit berechnetem Zustand (TODO c60bf914, bis V92 „Meilenstein“). {@code state} auf
 * Basis von {@code endDate}; ohne Zeitraum nur OPEN oder REACHED (in der Oberflaeche „abgeschlossen“).
 * {@code total}/{@code done} zaehlen die zugeordneten TODOs ohne ARCHIVED, {@code openQuestions} die
 * aktiven darin mit offener Frage. {@code reopened}: war schon einmal abgeschlossen
 * ({@code completedAt} gesetzt), ist es jetzt aber nicht mehr – etwa weil ein neues TODO dazukam.
 */
public record GoalWorkPackageResponse(
        String id,
        String goalId,
        String projectId,
        String title,
        Instant startDate,
        Instant endDate,
        int position,
        GoalDueState state,
        long total,
        long done,
        long openQuestions,
        Instant completedAt,
        boolean reopened,
        Instant createdAt,
        Instant updatedAt
) {}
