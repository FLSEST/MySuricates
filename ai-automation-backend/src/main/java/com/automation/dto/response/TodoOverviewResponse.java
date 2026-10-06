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
         * Appointments of active goals that need attention (TODO 259f4f66, c60bf914): milestones due soon or
         * overdue and overdue work packages with open TODOs, each with {@code kind}.
         */
        List<MilestoneAttentionItem> milestonesDue
) {
    /** Form without {@code milestonesDue} - for callers and test fixtures from before. */
    public TodoOverviewResponse(List<ProjectRollup> projects, List<NeedsYouItem> needsYou, Instant generatedAt,
                                int staleThresholdDays) {
        this(projects, needsYou, generatedAt, staleThresholdDays, List.of());
    }
}
