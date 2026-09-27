package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Neuer Meilenstein – ein Termin fuers ganze Ziel (TODO c60bf914).
 *
 * @param dueDate        Pflicht, ISO-Zeitpunkt ({@code 2026-10-15T21:59:59Z}) oder ISO-Datum
 *                       ({@code 2026-10-15} = Ende dieses Tages in UTC)
 * @param workPackageIds optional, Arbeitspakete desselben Ziels, von denen er abhaengt; ein fremdes = 400
 */
public record CreateGoalMilestoneRequest(
        @NotBlank String title,
        @NotBlank String dueDate,
        List<String> workPackageIds
) {
    public CreateGoalMilestoneRequest(String title, String dueDate) {
        this(title, dueDate, null);
    }
}
