package com.automation.modules;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRiskTest {

    @Test
    void outwardFacingModulesDeclareTheirRisks() {
        assertThat(ModuleCatalog.INTEGRATIONS.risks())
                .containsExactlyInAnyOrder(ModuleRisk.CREDENTIALS, ModuleRisk.NETWORK);
        assertThat(ModuleCatalog.WEBHOOKS.risks()).containsExactly(ModuleRisk.NETWORK);
        assertThat(ModuleCatalog.TERMINAL.risks()).containsExactly(ModuleRisk.HOST_ACCESS);
        assertThat(ModuleCatalog.STAGING.risks()).containsExactlyInAnyOrder(ModuleRisk.HOST_ACCESS, ModuleRisk.NETWORK);
    }

    @Test
    void risksOnlyAppearOutsideThePublicPresets() {
        // the public core/research presets must not switch on anything that exposes the host or network
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> !m.risks().isEmpty())
                .forEach(m -> {
                    assertThat(ModulePreset.CORE.enables(m)).as(m.key()).isFalse();
                    assertThat(ModulePreset.RESEARCH.enables(m)).as(m.key()).isFalse();
                });
    }

    @Test
    void everyRiskIsCarriedBySomeModule() {
        for (ModuleRisk risk : ModuleRisk.values()) {
            assertThat(ModuleCatalog.values()).as(risk.name()).anyMatch(m -> m.risks().contains(risk));
        }
    }
}
