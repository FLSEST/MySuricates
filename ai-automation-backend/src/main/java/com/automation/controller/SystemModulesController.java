package com.automation.controller;

import com.automation.dto.request.ModuleToggleRequest;
import com.automation.dto.response.SystemModulesResponse;
import com.automation.modules.ModuleOverrideService;
import com.automation.modules.ModuleRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime module switches for the settings UI. Answers 403 {@code MODULES_NOT_EDITABLE} unless
 * the startup configuration sets {@code automation.modules.ui-editable=true}; hardening switches
 * are never switchable here. Current state: {@code GET /api/v1/system/capabilities}.
 */
@RestController
@RequestMapping("/api/v1/system/modules")
@RequiredArgsConstructor
public class SystemModulesController {

    private final ModuleOverrideService overrides;
    private final ModuleRegistry registry;

    @PutMapping("/{name}")
    public SystemModulesResponse setEnabled(@PathVariable String name, @Valid @RequestBody ModuleToggleRequest request) {
        overrides.setEnabled(name, request.enabled());
        return SystemModulesResponse.of(registry);
    }

    @DeleteMapping("/{name}/override")
    public SystemModulesResponse resetToConfig(@PathVariable String name) {
        overrides.resetToConfig(name);
        return SystemModulesResponse.of(registry);
    }
}
