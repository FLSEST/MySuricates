package com.automation.dto.request;

import com.automation.entity.Goal;

/**
 * Teilaenderung eines Ziels: {@code null} laesst ein Feld stehen, ein leerer
 * {@code targetDate}/{@code description} leert es.
 */
public record UpdateGoalRequest(
        String title,
        String description,
        Goal.Status status,
        String targetDate
) {}
