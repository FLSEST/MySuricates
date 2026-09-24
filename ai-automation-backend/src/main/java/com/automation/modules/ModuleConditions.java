package com.automation.modules;

/**
 * SpEL conditions for {@code @EventListener(condition = ...)} so a listener of a disabled module
 * is skipped without touching its class body (useful for classes pinned by the size ratchet).
 * They resolve the {@code moduleRegistry} bean defined in {@link ModuleConfig}.
 */
public final class ModuleConditions {

    private static final String PREFIX = "@moduleRegistry.isEnabled(T(com.automation.modules.ModuleCatalog).";

    public static final String FACTORY = PREFIX + "FACTORY)";
    public static final String ASSISTANT = PREFIX + "ASSISTANT)";

    private ModuleConditions() {
    }
}
