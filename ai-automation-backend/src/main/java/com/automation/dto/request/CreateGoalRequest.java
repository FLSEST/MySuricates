package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * New goal (TODO dc0566f8).
 *
 * @param targetDate optional, ISO date ({@code 2026-10-15}) or timestamp
 * @param projectIds optional, the goal's projects in this order (TODO c60bf914)
 */
public record CreateGoalRequest(
        @NotBlank String title,
        String description,
        String targetDate,
        List<String> projectIds
) {
    /** Form without projects – for callers from before. */
    public CreateGoalRequest(String title, String description, String targetDate) {
        this(title, description, targetDate, null);
    }
}
