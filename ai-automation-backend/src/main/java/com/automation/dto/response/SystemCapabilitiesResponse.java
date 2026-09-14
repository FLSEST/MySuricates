package com.automation.dto.response;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

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
        boolean modulesUiEditable
) {
    public SystemCapabilitiesResponse(String apiVersion, String appVersion, String buildType, String environment,
                                      List<RouteCapabilityResponse> routes, List<BlockTypeCapabilityResponse> blockTypes,
                                      String preset, List<ModuleCapabilityResponse> modules, Instant generatedAt) {
        this(apiVersion, appVersion, buildType, environment, routes, blockTypes, preset, modules, generatedAt, false);
    }

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
            boolean overridden
    ) {
        public ModuleCapabilityResponse(String name, String kind, boolean enabled, String tier, String maturity,
                                        List<String> risks, List<String> requires, String property, String envVar,
                                        String description) {
            this(name, kind, enabled, tier, maturity, risks, requires, property, envVar, description, enabled, false);
        }

        public static ModuleCapabilityResponse from(ModuleRegistry.ModuleStatus status) {
            ModuleCatalog m = status.module();
            return new ModuleCapabilityResponse(
                    m.key(),
                    apiKey(m.kind()),
                    status.enabled(),
                    apiKey(m.tier()),
                    apiKey(m.maturity()),
                    m.risks().stream().map(ModuleCapabilityResponse::apiKey).sorted().toList(),
                    m.requires().stream().map(ModuleCatalog::key).toList(),
                    m.property(),
                    m.envVar(),
                    m.description(),
                    status.configured(),
                    status.overridden());
        }

        /** HOST_ACCESS → host-access */
        private static String apiKey(Enum<?> value) {
            return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
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
