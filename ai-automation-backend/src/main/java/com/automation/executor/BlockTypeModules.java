package com.automation.executor;

import com.automation.modules.ModuleCatalog;

import java.util.Map;
import java.util.Optional;

/**
 * Which block types belong to an optional module — one list for both sides of that fact.
 *
 * <p>Side one: the executor refuses to run the block when the module is off (each executor checks
 * {@code ModuleRegistry} itself, as background paths must). Side two: {@code GET
 * /system/capabilities} does not offer the type, so the editor never puts a block into a workflow
 * that this instance cannot run.
 *
 * <p>Saving stays possible either way — {@link BlockTypeRegistry} keeps collecting every executor's
 * types, so switching a module off never makes an existing definition unsavable.
 *
 * <p>Pinned by {@code BlockTypeModuleGateTest}: every entry needs an executor that refuses.
 */
public final class BlockTypeModules {

    private static final Map<String, ModuleCatalog> REQUIRED = Map.of(
            "skill-capability", ModuleCatalog.SKILLS,
            "dashboard-report", ModuleCatalog.DASHBOARD_REPORT);

    private BlockTypeModules() {
    }

    /** The module this block type needs, or empty for a type every instance can run. */
    public static Optional<ModuleCatalog> requiredFor(String blockType) {
        return blockType == null ? Optional.empty() : Optional.ofNullable(REQUIRED.get(blockType));
    }

    /** The whole mapping — for the capabilities filter and for guard tests. */
    public static Map<String, ModuleCatalog> all() {
        return REQUIRED;
    }
}
