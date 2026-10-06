package com.automation.dto.request;

import java.util.List;

/**
 * Partial update of a milestone (TODO c60bf914): {@code null} leaves a field as it is.
 * {@code workPackageIds} replaces the set of linked packages ({@code []} unlinks all).
 * {@code reached} marks a milestone WITHOUT linked packages as reached by hand
 * ({@code true}) or not ({@code false}); with packages it follows from them – then
 * {@code reached} is a 400.
 */
public record UpdateGoalMilestoneRequest(
        String title,
        String dueDate,
        List<String> workPackageIds,
        Boolean reached
) {}
