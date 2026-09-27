package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Neues Arbeitspaket (TODO c60bf914).
 *
 * @param projectId Projekt des Pakets; Pflicht, ausser das Ziel hat genau ein Projekt – dann dieses.
 *                  Ein Projekt, das dem Ziel noch nicht zugeordnet ist, kommt dazu.
 * @param startDate optional, zusammen mit {@code endDate}; ISO-Zeitpunkt oder ISO-Datum
 *                  ({@code 2026-10-01} = Beginn dieses Tages in UTC)
 * @param endDate   optional, zusammen mit {@code startDate}; ISO-Zeitpunkt oder ISO-Datum
 *                  ({@code 2026-10-15} = Ende dieses Tages in UTC). Beide fehlen = ohne Zeitraum.
 */
public record CreateGoalWorkPackageRequest(
        @NotBlank String title,
        String projectId,
        String startDate,
        String endDate
) {}
