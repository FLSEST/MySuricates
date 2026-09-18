package com.automation.modules;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Startup preset for {@link ModuleKind#FEATURE} modules ({@code automation.modules.preset}). */
public enum ModulePreset {
    /** Core plus every {@link ModuleTier#STANDARD} module. Public default. */
    CORE,
    /** Core plus {@link ModuleTier#RESEARCH} modules. */
    RESEARCH,
    /** Every feature module. */
    FULL;

    boolean enables(ModuleCatalog module) {
        return switch (this) {
            case CORE -> module.tier() == ModuleTier.STANDARD;
            case RESEARCH -> module.tier() == ModuleTier.STANDARD || module.tier() == ModuleTier.RESEARCH;
            case FULL -> true;
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ModulePreset parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CORE;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(p -> p.key().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown automation.modules.preset '" + raw
                        + "' — expected one of " + Arrays.stream(values()).map(ModulePreset::key)
                        .collect(Collectors.joining(", "))));
    }
}
