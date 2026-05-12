package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

public record SystemCapabilitiesResponse(
        String apiVersion,
        String appVersion,
        String buildType,
        String environment,
        List<RouteCapabilityResponse> routes,
        List<BlockTypeCapabilityResponse> blockTypes,
        Instant generatedAt
) {
    public record RouteCapabilityResponse(
            String path,
            List<String> methods,
            String controller,
            String handler
    ) {}

    public record BlockTypeCapabilityResponse(
            String type,
            String summary
    ) {}
}
