package com.automation.dto.response;

import com.automation.modules.ModuleRegistry;

import java.util.List;

/**
 * Module state after a runtime switch — the same shape the capabilities endpoint reports.
 *
 * @param uiEditable {@code automation.modules.ui-editable}
 */
public record SystemModulesResponse(
        String preset,
        boolean uiEditable,
        List<SystemCapabilitiesResponse.ModuleCapabilityResponse> modules
) {
    public static SystemModulesResponse of(ModuleRegistry registry) {
        return new SystemModulesResponse(registry.preset().key(), registry.uiEditable(),
                registry.describe().stream().map(SystemCapabilitiesResponse.ModuleCapabilityResponse::from).toList());
    }
}
