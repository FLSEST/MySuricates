package com.automation.modules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleKindTest {

    @Test
    void prefixesMatchTheDocumentedPropertyTrees() {
        assertThat(ModuleKind.FEATURE.propertyPrefix()).isEqualTo("automation.modules");
        assertThat(ModuleKind.HARDENING.propertyPrefix()).isEqualTo("automation.hardening");
    }

    @Test
    void uiEditableSwitchLivesBelowTheFeaturePrefix() {
        assertThat(ModuleRegistry.UI_EDITABLE_PROPERTY).isEqualTo("automation.modules.ui-editable");
    }

    @Test
    void everyKindIsUsedByTheCatalog() {
        for (ModuleKind kind : ModuleKind.values()) {
            assertThat(ModuleCatalog.values()).as(kind.name()).anyMatch(m -> m.kind() == kind);
        }
    }
}
