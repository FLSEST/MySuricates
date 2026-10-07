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
        /** Every optional module and hardening switch with its current state. */
        List<ModuleCapabilityResponse> modules,
        Instant generatedAt,
        /** {@code automation.modules.ui-editable}: feature modules can be switched in the settings UI. */
        boolean modulesUiEditable,
        /** {@code automation.executions.allow-delete}: runs may be deleted via the API (startup-only). */
        boolean runDeletionAllowed,
        /** What this instance got from the host for the AI CLIs; read-only (262948a4). */
        ContainerAccessResponse containerAccess
) {
    public SystemCapabilitiesResponse(String apiVersion, String appVersion, String buildType, String environment,
                                      List<RouteCapabilityResponse> routes, List<BlockTypeCapabilityResponse> blockTypes,
                                      String preset, List<ModuleCapabilityResponse> modules, Instant generatedAt) {
        this(apiVersion, appVersion, buildType, environment, routes, blockTypes, preset, modules, generatedAt, false, true,
                null);
    }

    /**
     * What comes from the host into this instance, as decided at start by scripts/lib/host-mounts.sh.
     * The app cannot change any of it: the switches live in {@code switchSource} (outside the app) and
     * take effect on the next start. Only names and yes/no values, never a token or file content.
     *
     * @param runtime      {@code container} or {@code host} (a host instance has no such boundary)
     * @param aiFromHost   {@code programs} | {@code login} | {@code all}; null on the host
     * @param dockerSocket the Docker socket is reachable from this instance
     * @param switchSource where the switches are changed; null on the host
     * @param switchPrefix prefix of the switch names there, e.g. {@code STABLE_}; null on the host
     */
    public record ContainerAccessResponse(
            String runtime,
            String aiFromHost,
            boolean dockerSocket,
            String switchSource,
            String switchPrefix,
            List<ProviderAccessResponse> providers
    ) {}

    /**
     * @param selected part of the provider selection (always true on the host)
     * @param program  the CLI is on the PATH of this instance
     * @param login    a login is present, by the rule of the AI provider card (7296ad45); null when no
     *                 provider with this program is registered
     */
    public record ProviderAccessResponse(String id, boolean selected, boolean program, Boolean login) {}

    /**
     * @param kind     feature | hardening
     * @param tier     research | advanced | hardening
     * @param maturity stable | experimental | legacy
     * @param risks    credentials | network | host-access
     * @param property property that switches the module, e.g. {@code automation.modules.webhooks}
     * @param envVar   environment variable form, e.g. {@code AUTOMATION_MODULES_WEBHOOKS}
     * @param configured state from the startup configuration alone
     * @param overridden true if a settings-UI switch decides the state
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
            String description,
            boolean configured,
            boolean overridden,
            /** Decided by the startup configuration alone, even with {@code ui-editable} (hardening, mcp). */
            boolean startupOnly
    ) {
        public ModuleCapabilityResponse(String name, String kind, boolean enabled, String tier, String maturity,
                                        List<String> risks, List<String> requires, String property, String envVar,
                                        String description) {
            this(name, kind, enabled, tier, maturity, risks, requires, property, envVar, description, enabled, false,
                    "hardening".equals(kind));
        }
    }

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
