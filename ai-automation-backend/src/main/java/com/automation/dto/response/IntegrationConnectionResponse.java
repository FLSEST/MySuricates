package com.automation.dto.response;

import java.time.Instant;

/** One named connection of an integration provider (0b3cbcc3). */
public record IntegrationConnectionResponse(
        /** Connection name; {@code default} for the provider's original connection. */
        String name,
        /** {@code CONNECTED}, {@code DISCONNECTED} or {@code EXPIRED}. */
        String status,
        /** Whether the connection has the credentials it needs to connect (for OAuth: own, default row or env). */
        boolean configured,
        String accountEmail,
        String accountName,
        Instant connectedSince,
        String scopes
) {
}
