package com.automation.modules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleMaturityTest {

    @Test
    void labelsAreStableExperimentalLegacy() {
        // names are shown on the settings page and serialized to the frontend — renaming breaks the UI
        assertThat(ModuleMaturity.values()).extracting(Enum::name)
                .containsExactly("STABLE", "EXPERIMENTAL", "LEGACY");
    }

    @Test
    void catalogAssignsHonestLabels() {
        assertThat(ModuleCatalog.CHAINS.maturity()).isEqualTo(ModuleMaturity.STABLE);
        assertThat(ModuleCatalog.ASSISTANT.maturity()).isEqualTo(ModuleMaturity.EXPERIMENTAL);
        assertThat(ModuleCatalog.CONVERSION.maturity()).isEqualTo(ModuleMaturity.LEGACY);
    }

    @Test
    void everyLabelIsInUse() {
        for (ModuleMaturity maturity : ModuleMaturity.values()) {
            assertThat(ModuleCatalog.values()).as(maturity.name()).anyMatch(m -> m.maturity() == maturity);
        }
    }
}
