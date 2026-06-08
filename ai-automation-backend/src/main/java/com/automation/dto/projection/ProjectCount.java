package com.automation.dto.projection;

/**
 * Generic per-project count projection (e.g. stale-todo count keyed by project).
 */
public record ProjectCount(
        String projectId,
        long cnt
) {}
