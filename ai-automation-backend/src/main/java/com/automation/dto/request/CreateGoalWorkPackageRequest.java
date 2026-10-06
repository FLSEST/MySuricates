package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * New work package (TODO c60bf914).
 *
 * @param projectId project of the package; mandatory, unless the goal has exactly one project – then that one.
 *                  A project not yet assigned to the goal is added.
 * @param startDate optional, together with {@code endDate}; ISO timestamp or ISO date
 *                  ({@code 2026-10-01} = start of that day in UTC)
 * @param endDate   optional, together with {@code startDate}; ISO timestamp or ISO date
 *                  ({@code 2026-10-15} = end of that day in UTC). Both missing = no period.
 */
public record CreateGoalWorkPackageRequest(
        @NotBlank String title,
        String projectId,
        String startDate,
        String endDate
) {}
