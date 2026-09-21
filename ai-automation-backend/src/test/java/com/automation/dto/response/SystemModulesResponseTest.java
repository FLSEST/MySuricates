package com.automation.dto.response;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemModulesResponseTest {

    @Test
    void of_reports_preset_ui_editable_and_every_module() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "research", "ui-editable", "true"), Map.of());

        SystemModulesResponse response = SystemModulesResponse.of(registry);

        assertThat(response.preset()).isEqualTo("research");
        assertThat(response.uiEditable()).isTrue();
        assertThat(response.modules()).hasSize(ModuleCatalog.values().length);
        assertThat(response.modules()).extracting(SystemCapabilitiesResponse.ModuleCapabilityResponse::name)
                .containsExactly(java.util.Arrays.stream(ModuleCatalog.values()).map(ModuleCatalog::key).toArray(String[]::new));
    }

    @Test
    void of_reflects_runtime_overrides() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "full", "ui-editable", "true"), Map.of());
        registry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, false));

        SystemCapabilitiesResponse.ModuleCapabilityResponse module = SystemModulesResponse.of(registry).modules().stream()
                .filter(m -> m.name().equals(ModuleCatalog.DASHBOARD_REPORT.key()))
                .findFirst().orElseThrow();

        assertThat(module.enabled()).isFalse();
        assertThat(module.configured()).isTrue();
        assertThat(module.overridden()).isTrue();
    }

    @Test
    void of_without_ui_editable_reports_false() {
        SystemModulesResponse response = SystemModulesResponse.of(ModuleRegistry.of(Map.of(), Map.of()));

        assertThat(response.uiEditable()).isFalse();
        assertThat(response.preset()).isEqualTo("core");
    }
}
