package com.automation.modules;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Serializes read, check, store and apply of the overrides: two toggles cannot interleave, so none
     * is lost and the stored overrides are always the applied ones. A lock instead of {@code synchronized}:
     * on Java 21 a monitor held across blocking I/O (the store write) pins the carrier of the virtual
     * request thread, a waiting toggle parks here instead. The activity log entry and the event follow
     * after the lock is released — listeners run synchronously and may take long (the audit fingerprint
     * migration walks every scope). Each event is published after its state was applied and describes a
     * transition that really happened; with concurrent toggles of the same module, events may reach
     * listeners out of order, so a listener that must not act on a stale one checks
     * {@link ModuleRegistry} (as the audit fingerprint migration does).
     */
    private final ReentrantLock lock = new ReentrantLock();

    public ModuleRegistry.ModuleStatus setEnabled(String name, boolean enabled) {
        ModuleCatalog module = resolve(name);
        return change(module, overrides -> overrides.put(module, enabled),
                "Module '" + module.key() + "' " + (enabled ? "enabled" : "disabled") + " in settings");
    }

    /** Drops the UI override: the module follows the startup configuration again. */
    public ModuleRegistry.ModuleStatus resetToConfig(String name) {
        ModuleCatalog module = resolve(name);
        return change(module, overrides -> overrides.remove(module),
                "Module '" + module.key() + "' reset to startup configuration in settings");
    }

    private ModuleRegistry.ModuleStatus change(ModuleCatalog module, Consumer<Map<ModuleCatalog, Boolean>> edit,
                                               String message) {
        boolean before;
        ModuleRegistry.ModuleStatus status;
        lock.lock();
        try {
            before = registry.isEnabled(module);
            Map<ModuleCatalog, Boolean> next = new EnumMap<>(ModuleCatalog.class);
            next.putAll(registry.uiOverrides());
            edit.accept(next);
            Map<ModuleCatalog, Boolean> normalized = registry.checkUiOverrides(next);
            store.save(normalized);
            registry.applyUiOverrides(normalized);
            status = registry.status(module);
        } finally {
            lock.unlock();
        }
        activityLog.warn(LogCategory.SECURITY, message + " (" + (before ? "on" : "off") + " → "
                + (status.enabled() ? "on" : "off") + ", startup configuration: " + (status.configured() ? "on" : "off")
                + ")", "ModuleOverrideService");
        // Only on a real change: background paths of the module (jobs, listeners) catch up on the
        // part that otherwise runs only at startup.
        if (before != status.enabled()) {
            eventPublisher.publishEvent(new ModuleStateChangedEvent(this, module, status.enabled()));
        }
        return status;
    }

    private static ModuleCatalog resolve(String name) {
        return ModuleCatalog.byKey(name).orElseThrow(() -> new ResourceNotFoundException("Module", name));
    }
}
