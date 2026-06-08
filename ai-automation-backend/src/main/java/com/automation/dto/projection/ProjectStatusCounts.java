package com.automation.dto.projection;

/**
 * Lean per-project aggregation for the Projekt-Cockpit overview.
 * Built via a single GROUP BY t.projectId with SUM(CASE...) projections —
 * loads zero TEXT blobs (description/context), safe under pool-size=1.
 * userActive/machineActive count only active statuses (OPEN/IN_PROGRESS/BACKLOG).
 */
public record ProjectStatusCounts(
        String projectId,
        long open,
        long inProgress,
        long done,
        long backlog,
        long archived,
        long userActive,
        long machineActive
) {}
