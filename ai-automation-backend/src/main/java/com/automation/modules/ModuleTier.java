package com.automation.modules;

/**
 * Grouping of optional modules. Presets switch whole tiers of {@link ModuleKind#FEATURE}
 * modules; {@link #HARDENING} flags are never touched by presets and keep their own defaults.
 */
public enum ModuleTier {
    /**
     * On in every preset — including the public {@code core} default — but still switchable off.
     * For a feature that belongs to the normal picture of the platform and costs nobody anything
     * to have, yet is not so entangled that it has to be core.
     */
    STANDARD,
    /** Research features that carry the project's story but add surface for newcomers. */
    RESEARCH,
    /** Outward-facing, low-use or legacy features. Only enabled by the {@code full} preset. */
    ADVANCED,
    /** Optional workflow hardening switches ({@code automation.hardening.*}). */
    HARDENING
}
