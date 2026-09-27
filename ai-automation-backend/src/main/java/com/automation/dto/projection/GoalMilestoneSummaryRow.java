package com.automation.dto.projection;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * Ein Meilenstein ohne seine Verknuepfungen, dafuer mit Paket-Zaehlern aus Unterabfragen
 * (TODO c60bf914): {@code packagesTotal} verknuepfte Arbeitspakete, {@code packagesDone} davon
 * abgeschlossen. Genug fuer den Zustand – fuer die Zielliste und „Braucht dich“.
 */
public record GoalMilestoneSummaryRow(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        Instant reachedAt,
        long packagesTotal,
        long packagesDone
) {
    /** Mit Paketen erreicht, wenn alle abgeschlossen sind; ohne Pakete, wenn von Hand markiert. */
    public boolean reached() {
        return packagesTotal > 0 ? packagesDone >= packagesTotal : reachedAt != null;
    }

    public GoalDueState state(Instant now) {
        return GoalDueState.of(dueDate, reached(), now);
    }
}
