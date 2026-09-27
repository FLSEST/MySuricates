package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Cross-project TODO cockpit payload: a per-project rollup list plus a global
 * "Braucht dich" focus lane. Assembled from a handful of lean GROUP BY queries
 * — intentionally carries no description/context blobs (pool-size=1).
 */
public record TodoOverviewResponse(
        List<ProjectRollup> projects,
        List<NeedsYouItem> needsYou,
        Instant generatedAt,
        int staleThresholdDays,
        /**
         * Termine aktiver Ziele, die Aufmerksamkeit brauchen (TODO 259f4f66, c60bf914): bald faellige oder
         * ueberfaellige Meilensteine und ueberfaellige Arbeitspakete mit offenen TODOs, je mit {@code kind}.
         */
        List<MilestoneAttentionItem> milestonesDue
) {
    /** Form ohne {@code milestonesDue} – fuer Aufrufer und Test-Fixturen von vorher. */
    public TodoOverviewResponse(List<ProjectRollup> projects, List<NeedsYouItem> needsYou, Instant generatedAt,
                                int staleThresholdDays) {
        this(projects, needsYou, generatedAt, staleThresholdDays, List.of());
    }
}
