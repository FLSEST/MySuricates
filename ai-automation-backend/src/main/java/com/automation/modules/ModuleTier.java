package com.automation.modules;

/**
 * Grouping of optional modules. Presets switch whole tiers of {@link ModuleKind#FEATURE}
 * modules; {@link #HARDENING} flags are never touched by presets and keep their own defaults.
 */
public enum ModuleTier {
    /** Research features that carry the project's story but add surface for newcomers. */
    RESEARCH,
    /** Outward-facing, low-use or legacy features. Only enabled by the {@code full} preset. */
    ADVANCED,
    /** Optional workflow hardening switches ({@code automation.hardening.*}). */
    HARDENING
}
