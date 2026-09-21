package com.automation.modules;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleTierTest {

    @Test
    void onlyTheHardeningTierMapsToHardeningKind() {
        for (ModuleCatalog m : ModuleCatalog.values()) {
            assertThat(m.kind() == ModuleKind.HARDENING).as(m.key()).isEqualTo(m.tier() == ModuleTier.HARDENING);
        }
    }

    @Test
    void standardTierIsOnInEveryPreset() {
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.tier() == ModuleTier.STANDARD)
                .forEach(m -> Arrays.stream(ModulePreset.values())
                        .forEach(p -> assertThat(p.enables(m)).as(p + "/" + m.key()).isTrue()));
    }

    @Test
    void advancedTierIsOnlyEnabledByFull() {
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.tier() == ModuleTier.ADVANCED)
                .forEach(m -> {
                    assertThat(ModulePreset.CORE.enables(m)).as(m.key()).isFalse();
                    assertThat(ModulePreset.RESEARCH.enables(m)).as(m.key()).isFalse();
                    assertThat(ModulePreset.FULL.enables(m)).as(m.key()).isTrue();
                });
    }

    @Test
    void hardeningFlagsKeepTheirOwnDefaultRegardlessOfPreset() {
        // presets never switch hardening flags; the registry uses defaultEnabled for them
        ModuleRegistry core = ModuleRegistry.of(java.util.Map.of("preset", "core"), java.util.Map.of());
        ModuleRegistry full = ModuleRegistry.of(java.util.Map.of("preset", "full"), java.util.Map.of());
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.tier() == ModuleTier.HARDENING)
                .forEach(m -> {
                    assertThat(core.isEnabled(m)).as(m.key()).isEqualTo(m.defaultEnabled());
                    assertThat(full.isEnabled(m)).as(m.key()).isEqualTo(m.defaultEnabled());
                });
    }

    @Test
    void everyTierHasAtLeastOneModule() {
        for (ModuleTier tier : ModuleTier.values()) {
            assertThat(ModuleCatalog.values()).as(tier.name()).anyMatch(m -> m.tier() == tier);
        }
    }
}
