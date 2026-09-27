package com.automation.dto.request;

import java.util.List;

/**
 * Teilaenderung eines Meilensteins (TODO c60bf914): {@code null} laesst ein Feld stehen.
 * {@code workPackageIds} ersetzt die Menge der verknuepften Pakete ({@code []} loest alle).
 * {@code reached} markiert einen Meilenstein OHNE verknuepfte Pakete von Hand als erreicht
 * ({@code true}) oder nicht ({@code false}); mit Paketen ergibt sich das aus ihnen – dann ist
 * {@code reached} ein 400.
 */
public record UpdateGoalMilestoneRequest(
        String title,
        String dueDate,
        List<String> workPackageIds,
        Boolean reached
) {}
