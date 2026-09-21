package com.automation.modules;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModulePresetTest {

    @Test
    void parseDefaultsToCoreForMissingOrBlankValue() {
        assertThat(ModulePreset.parse(null)).isEqualTo(ModulePreset.CORE);
        assertThat(ModulePreset.parse("")).isEqualTo(ModulePreset.CORE);
        assertThat(ModulePreset.parse("   ")).isEqualTo(ModulePreset.CORE);
    }

    @Test
    void parseIgnoresCaseAndSurroundingWhitespace() {
        assertThat(ModulePreset.parse("FULL")).isEqualTo(ModulePreset.FULL);
        assertThat(ModulePreset.parse(" Research ")).isEqualTo(ModulePreset.RESEARCH);
    }

    @Test
    void parseFailsLoudlyOnUnknownPresetAndListsTheValidOnes() {
        assertThatThrownBy(() -> ModulePreset.parse("everything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'everything'")
                .hasMessageContaining("core, research, full");
    }

    @Test
    void keyIsTheLowercaseName() {
        assertThat(ModulePreset.FULL.key()).isEqualTo("full");
        Arrays.stream(ModulePreset.values()).forEach(p -> assertThat(ModulePreset.parse(p.key())).isEqualTo(p));
    }

    @Test
    void coreEnablesOnlyTheStandardTier() {
        assertThat(ModulePreset.CORE.enables(ModuleCatalog.DASHBOARD_REPORT)).isTrue();
        assertThat(ModulePreset.CORE.enables(ModuleCatalog.CHAINS)).isFalse();
        assertThat(ModulePreset.CORE.enables(ModuleCatalog.WEBHOOKS)).isFalse();
    }

    @Test
    void researchAddsTheResearchTierButNotAdvanced() {
        assertThat(ModulePreset.RESEARCH.enables(ModuleCatalog.DASHBOARD_REPORT)).isTrue();
        assertThat(ModulePreset.RESEARCH.enables(ModuleCatalog.AUDIT)).isTrue();
        assertThat(ModulePreset.RESEARCH.enables(ModuleCatalog.TERMINAL)).isFalse();
    }

    @Test
    void presetsAreCumulative() {
        for (ModuleCatalog m : ModuleCatalog.values()) {
            if (ModulePreset.CORE.enables(m)) {
                assertThat(ModulePreset.RESEARCH.enables(m)).as(m.key()).isTrue();
            }
            if (ModulePreset.RESEARCH.enables(m)) {
                assertThat(ModulePreset.FULL.enables(m)).as(m.key()).isTrue();
            }
        }
        assertThat(ModulePreset.FULL.enables(ModuleCatalog.WISHES)).isTrue();
    }
}
