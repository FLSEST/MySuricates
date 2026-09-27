package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Neues Ziel (TODO dc0566f8).
 *
 * @param targetDate optional, ISO-Datum ({@code 2026-10-15}) oder -Zeitpunkt
 * @param projectIds optional, die Projekte des Ziels in dieser Reihenfolge (TODO c60bf914)
 */
public record CreateGoalRequest(
        @NotBlank String title,
        String description,
        String targetDate,
        List<String> projectIds
) {
    /** Form ohne Projekte – fuer Aufrufer von vorher. */
    public CreateGoalRequest(String title, String description, String targetDate) {
        this(title, description, targetDate, null);
    }
}
