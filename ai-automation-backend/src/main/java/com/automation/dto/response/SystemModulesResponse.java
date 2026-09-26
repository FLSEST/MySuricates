package com.automation.dto.response;

import java.util.List;

/**
 * Module state after a runtime switch — the same shape the capabilities endpoint reports; built by
 * {@link com.automation.mapper.ModuleCapabilityMapper#modulesResponse}.
 *
 * @param uiEditable {@code automation.modules.ui-editable}
 */
public record SystemModulesResponse(
        String preset,
        boolean uiEditable,
        List<SystemCapabilitiesResponse.ModuleCapabilityResponse> modules
) {}
