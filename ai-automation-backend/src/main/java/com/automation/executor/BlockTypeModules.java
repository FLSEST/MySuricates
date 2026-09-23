package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.modules.ModuleCatalog;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which block types belong to an optional module — one list for both sides of that fact.
 *
 * <p>Side one: the executor refuses to run the block when the module is off (each executor checks
 * {@code ModuleRegistry} itself, as background paths must). Side two: {@code GET
 * /system/capabilities} does not offer the type, so the editor never puts a block into a workflow
 * that this instance cannot run. The template picker reads the same list through
 * {@link #requiredBy(List)} and hides templates this instance cannot run (TODO 560d18dd).
 *
 * <p>Saving stays possible either way — {@link BlockTypeRegistry} keeps collecting every executor's
 * types, so switching a module off never makes an existing definition unsavable.
 *
 * <p>Pinned by {@code BlockTypeModuleGateTest} in both directions: every entry needs an executor
 * that refuses, and every executor that checks a module needs an entry.
 */
public final class BlockTypeModules {

    private static final Map<String, ModuleCatalog> REQUIRED = Map.of(
            "skill-capability", ModuleCatalog.SKILLS,
            "dashboard-report", ModuleCatalog.DASHBOARD_REPORT,
            "integration", ModuleCatalog.INTEGRATIONS);

    private BlockTypeModules() {
    }

    /** The module this block type needs, or empty for a type every instance can run. */
    public static Optional<ModuleCatalog> requiredFor(String blockType) {
        return blockType == null ? Optional.empty() : Optional.ofNullable(REQUIRED.get(blockType));
    }

    /**
     * The modules a block list needs, nested blocks included (switch cases and default, a plain
     * {@code blocks} list — the tree {@link NestedBlocks} walks), each once and in catalog order.
     * Empty when every instance can run the list.
     */
    public static Set<ModuleCatalog> requiredBy(List<BlockDefinition> blocks) {
        Set<ModuleCatalog> modules = EnumSet.noneOf(ModuleCatalog.class);
        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                if (block != null) {
                    requiredFor(block.getType()).ifPresent(modules::add);
                    NestedBlocks.visit(block.getConfig(), nested -> requiredFor(nested.type()).ifPresent(modules::add));
                }
            }
        }
        return modules;
    }

    /** The whole mapping — for the capabilities filter and for guard tests. */
    public static Map<String, ModuleCatalog> all() {
        return REQUIRED;
    }
}
