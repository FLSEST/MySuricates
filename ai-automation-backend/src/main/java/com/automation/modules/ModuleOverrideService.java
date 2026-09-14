package com.automation.modules;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Switches feature modules at runtime from the settings UI — only when
 * {@code automation.modules.ui-editable} is on. Validates via {@link ModuleRegistry}, stores the
 * result, then applies it (effective immediately) and records a SECURITY entry.
 */
@Service
@RequiredArgsConstructor
public class ModuleOverrideService {

    private final ModuleRegistry registry;
    private final ModuleOverrideStore store;
    private final ActivityLogService activityLog;

    public synchronized ModuleRegistry.ModuleStatus setEnabled(String name, boolean enabled) {
        ModuleCatalog module = resolve(name);
        Map<ModuleCatalog, Boolean> next = new EnumMap<>(ModuleCatalog.class);
        next.putAll(registry.uiOverrides());
        next.put(module, enabled);
        return change(module, next, "Module '" + module.key() + "' " + (enabled ? "enabled" : "disabled")
                + " in settings");
    }

    /** Drops the UI override: the module follows the startup configuration again. */
    public synchronized ModuleRegistry.ModuleStatus resetToConfig(String name) {
        ModuleCatalog module = resolve(name);
        Map<ModuleCatalog, Boolean> next = new EnumMap<>(ModuleCatalog.class);
        next.putAll(registry.uiOverrides());
        next.remove(module);
        return change(module, next, "Module '" + module.key() + "' reset to startup configuration in settings");
    }

    private ModuleRegistry.ModuleStatus change(ModuleCatalog module, Map<ModuleCatalog, Boolean> next, String message) {
        boolean before = registry.isEnabled(module);
        Map<ModuleCatalog, Boolean> normalized = registry.checkUiOverrides(next);
        store.save(normalized);
        registry.applyUiOverrides(normalized);
        ModuleRegistry.ModuleStatus status = registry.status(module);
        activityLog.warn(LogCategory.SECURITY, message + " (" + (before ? "on" : "off") + " → "
                + (status.enabled() ? "on" : "off") + ", startup configuration: " + (status.configured() ? "on" : "off")
                + ")", "ModuleOverrideService");
        return status;
    }

    private static ModuleCatalog resolve(String name) {
        return ModuleCatalog.byKey(name).orElseThrow(() -> new ResourceNotFoundException("Module", name));
    }
}
