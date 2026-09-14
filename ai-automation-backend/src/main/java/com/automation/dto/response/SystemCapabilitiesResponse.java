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
        /** Feature preset chosen at startup: core | research | full. */
        String preset,
        /** Every optional module and hardening switch with its startup decision (read-only). */
        List<ModuleCapabilityResponse> modules,
        Instant generatedAt
) {
    /**
     * @param kind     feature | hardening
     * @param tier     research | advanced | hardening
     * @param maturity stable | experimental | legacy
     * @param risks    credentials | network | host-access
     * @param property property that switches the module, e.g. {@code automation.modules.webhooks}
     * @param envVar   environment variable form, e.g. {@code AUTOMATION_MODULES_WEBHOOKS}
     */
    public record ModuleCapabilityResponse(
            String name,
            String kind,
            boolean enabled,
            String tier,
            String maturity,
            List<String> risks,
            List<String> requires,
            String property,
            String envVar,
            String description
    ) {}

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
