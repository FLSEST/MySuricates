package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Neuer Meilenstein – in der Oberflaeche „Teilziel“ (TODO 259f4f66, c60bf914).
 *
 * @param dueDate   optional, ISO-Zeitpunkt ({@code 2026-10-15T21:59:59Z}) oder ISO-Datum
 *                  ({@code 2026-10-15} = Ende dieses Tages in UTC); fehlt/leer = ohne Datum
 * @param projectId Projekt des Teilziels; Pflicht, ausser das Ziel hat genau ein Projekt – dann dieses.
 *                  Ein Projekt, das dem Ziel noch nicht zugeordnet ist, kommt dazu.
 */
public record CreateGoalMilestoneRequest(
        @NotBlank String title,
        String dueDate,
        String projectId
) {
    /** Form ohne Projekt – fuer Aufrufer von vorher. */
    public CreateGoalMilestoneRequest(String title, String dueDate) {
        this(title, dueDate, null);
    }
}
