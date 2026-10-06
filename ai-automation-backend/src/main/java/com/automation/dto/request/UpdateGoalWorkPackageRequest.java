package com.automation.dto.request;

/**
 * Partial update of a work package (TODO c60bf914): {@code null} leaves a field as it is, {@code ""}
 * clears {@code startDate}/{@code endDate}. The result must have both dates or neither, start never
 * after the end – otherwise 400. {@code projectId} changes the project, only as long as no TODOs are
 * assigned to the package.
 */
public record UpdateGoalWorkPackageRequest(
        String title,
        String projectId,
        String startDate,
        String endDate
) {}
