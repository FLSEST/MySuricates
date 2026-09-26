package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

public record IntegrationProviderResponse(
        String providerId,
        String displayName,
        String icon,
        String description,
        String status,
        boolean configured,
        boolean requiresCredentials,
        String accountEmail,
        String accountName,
        Instant connectedSince,
        String scopes,
        List<ScopeOptionResponse> optionalScopes,
        List<IntegrationActionResponse> actions,
        boolean supportsTaskExport,
        /** {@code VERIFIED} or {@code SIMULATED} — see {@code IntegrationVerification}. */
        String verification,
        String verifiedAgainst,
        /**
         * Every stored connection of this provider, {@code default} first (0b3cbcc3). The
         * top-level status/account fields describe the {@code default} connection.
         */
        List<IntegrationConnectionResponse> connections,
        /** {@code true}: connecting runs the OAuth consent; {@code false}: the credentials are the auth (PAT/API key). */
        boolean requiresOAuthFlow,
        /**
         * {@code true}: the provider works without a stored connection (TODO cdccc1e5) — without one
         * it is ready, not "setup required". A stored connection that is not connected still blocks.
         */
        boolean connectionOptional
) {
    public record ScopeOptionResponse(String scope, String label, boolean granted) {}
}
