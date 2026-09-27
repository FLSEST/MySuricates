package com.automation.dto.response;

import com.automation.entity.GoalMilestone;

import java.time.Instant;

/**
 * Ein Meilenstein – in der Oberflaeche „Teilziel“ – mit berechnetem Zustand (TODO 259f4f66).
 * {@code total}/{@code done} zaehlen die zugeordneten TODOs ohne ARCHIVED. {@code reopened}: war
 * schon einmal erreicht ({@code reachedAt} gesetzt), ist es jetzt aber nicht mehr – etwa weil ein
 * neues TODO dazukam.
 *
 * <p>Seit TODO c60bf914: {@code dueDate} kann null sein (dann ist {@code state} nur OPEN oder
 * REACHED), {@code projectId} ist das Projekt des Teilziels (null nur bei Altdaten),
 * {@code position} die Reihenfolge innerhalb dieses Projekts und {@code openQuestions} die Zahl der
 * aktiven TODOs darin mit offener Frage.
 */
public record GoalMilestoneResponse(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        GoalMilestone.State state,
        long total,
        long done,
        Instant reachedAt,
        boolean reopened,
        Instant createdAt,
        Instant updatedAt,
        String projectId,
        int position,
        long openQuestions
) {}
