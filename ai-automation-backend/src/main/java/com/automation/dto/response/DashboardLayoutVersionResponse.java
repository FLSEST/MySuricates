package com.automation.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** A dashboard layout version including the layout (`{ activeViewId, views[] }`). */
public record DashboardLayoutVersionResponse(
        String id,
        Instant createdAt,
        String deviceLabel,
        String name,
        int formatVersion,
        int viewCount,
        String contentHash,
        JsonNode layout) {
}
