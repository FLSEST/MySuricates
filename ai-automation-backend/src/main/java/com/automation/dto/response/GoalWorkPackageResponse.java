package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * A work package with computed state (TODO c60bf914, until V92 "milestone"). {@code state} based
 * on {@code endDate}; without a period only OPEN or REACHED (in the UI "abgeschlossen").
 * {@code total}/{@code done} count the assigned TODOs without ARCHIVED, {@code openQuestions} the
 * active ones in it with an open question. {@code reopened}: was completed once
 * ({@code completedAt} set), but no longer is - e.g. because a new TODO was added.
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
        Instant updatedAt,
        /** Non-archived TODOs in it with a decision and without an open question (TODO 68a5e24e). */
        long decided
) {}
