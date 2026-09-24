package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Neuer Meilenstein (TODO 259f4f66).
 *
 * @param dueDate Pflicht, ISO-Zeitpunkt ({@code 2026-10-15T21:59:59Z}) oder ISO-Datum
 *                ({@code 2026-10-15} = Ende dieses Tages in UTC)
 */
public record CreateGoalMilestoneRequest(
        @NotBlank String title,
        @NotBlank String dueDate
) {}
