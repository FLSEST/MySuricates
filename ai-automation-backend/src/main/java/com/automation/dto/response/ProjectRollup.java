package com.automation.dto.response;

/**
 * Per-project rollup card for the Projekt-Cockpit. Counts only; no blobs.
 * userActive/machineActive split the active workload (OPEN/IN_PROGRESS/BACKLOG)
 * by source so the cockpit can show "deins vs. Maschine".
 */
public record ProjectRollup(
        String projectId,
        String projectName,
        long open,
        long inProgress,
        long done,
        long backlog,
        long archived,
        long staleCount,
        long userActive,
        long machineActive,
        long needsYouCount
) {}
