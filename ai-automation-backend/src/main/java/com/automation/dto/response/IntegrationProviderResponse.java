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
        String accountEmail,
        String accountName,
        Instant connectedSince,
        String scopes,
        List<IntegrationActionResponse> actions
) {}
