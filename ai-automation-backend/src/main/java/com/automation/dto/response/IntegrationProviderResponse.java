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
        boolean supportsTaskExport
) {
    public record ScopeOptionResponse(String scope, String label, boolean granted) {}
}
