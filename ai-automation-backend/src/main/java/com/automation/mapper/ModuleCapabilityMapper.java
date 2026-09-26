package com.automation.mapper;

import com.automation.dto.response.SystemCapabilitiesResponse.ModuleCapabilityResponse;
import com.automation.dto.response.SystemModulesResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;

import java.util.List;
import java.util.Locale;

/**
 * Module state as the API reports it – for the capabilities endpoint and after a runtime switch.
 * Lives here rather than in the response records so that {@code dto.response} does not depend on
 * {@code modules}, which reaches back to the DTOs through {@code repository} (TODO 759b9fba).
 */
public final class ModuleCapabilityMapper {

    private ModuleCapabilityMapper() {
    }

    /** Every optional module and hardening switch with its current state. */
    public static List<ModuleCapabilityResponse> modules(ModuleRegistry registry) {
        return registry.describe().stream().map(ModuleCapabilityMapper::toResponse).toList();
    }

    /** Preset, {@code ui-editable} and every module – the answer of the module switch endpoints. */
    public static SystemModulesResponse modulesResponse(ModuleRegistry registry) {
        return new SystemModulesResponse(registry.preset().key(), registry.uiEditable(), modules(registry));
    }

    public static ModuleCapabilityResponse toResponse(ModuleRegistry.ModuleStatus status) {
        ModuleCatalog m = status.module();
        return new ModuleCapabilityResponse(
                m.key(),
                apiKey(m.kind()),
                status.enabled(),
                apiKey(m.tier()),
                apiKey(m.maturity()),
                m.risks().stream().map(ModuleCapabilityMapper::apiKey).sorted().toList(),
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
