package com.automation.dto.response;

import java.time.Instant;

/** A dashboard layout version without its content (list view). */
public record DashboardLayoutVersionSummary(
        String id,
        Instant createdAt,
        String deviceLabel,
        String name,
        int formatVersion,
        int viewCount,
        String contentHash) {
}
