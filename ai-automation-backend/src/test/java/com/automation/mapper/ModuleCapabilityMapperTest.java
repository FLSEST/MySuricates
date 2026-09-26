package com.automation.mapper;

import com.automation.dto.response.SystemCapabilitiesResponse;
import com.automation.dto.response.SystemModulesResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleCapabilityMapperTest {

    @Test
    void modulesResponse_reports_preset_ui_editable_and_every_module() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "research", "ui-editable", "true"), Map.of());

        SystemModulesResponse response = ModuleCapabilityMapper.modulesResponse(registry);

        assertThat(response.preset()).isEqualTo("research");
        assertThat(response.uiEditable()).isTrue();
        assertThat(response.modules()).hasSize(ModuleCatalog.values().length);
        assertThat(response.modules()).extracting(SystemCapabilitiesResponse.ModuleCapabilityResponse::name)
                .containsExactly(java.util.Arrays.stream(ModuleCatalog.values()).map(ModuleCatalog::key).toArray(String[]::new));
    }

    @Test
    void modulesResponse_reflects_runtime_overrides() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "full", "ui-editable", "true"), Map.of());
        registry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, false));

        SystemCapabilitiesResponse.ModuleCapabilityResponse module = ModuleCapabilityMapper.modulesResponse(registry)
                .modules().stream()
                .filter(m -> m.name().equals(ModuleCatalog.DASHBOARD_REPORT.key()))
                .findFirst().orElseThrow();

        assertThat(module.enabled()).isFalse();
        assertThat(module.configured()).isTrue();
        assertThat(module.overridden()).isTrue();
    }

    @Test
    void modulesResponse_without_ui_editable_reports_false() {
        SystemModulesResponse response = ModuleCapabilityMapper.modulesResponse(ModuleRegistry.of(Map.of(), Map.of()));

        assertThat(response.uiEditable()).isFalse();
        assertThat(response.preset()).isEqualTo("core");
    }

    @Test
    void toResponse_writes_enum_values_as_lower_case_api_keys_and_sorts_the_risks() {
        SystemCapabilitiesResponse.ModuleCapabilityResponse staging = ModuleCapabilityMapper.toResponse(
                new ModuleRegistry.ModuleStatus(ModuleCatalog.STAGING, true, false, true));

        assertThat(staging.name()).isEqualTo("staging");
        assertThat(staging.kind()).isEqualTo("feature");
        assertThat(staging.tier()).isEqualTo("advanced");
        assertThat(staging.maturity()).isEqualTo("experimental");
        assertThat(staging.risks()).containsExactly("host-access", "network");
        assertThat(staging.property()).isEqualTo(ModuleCatalog.STAGING.property());
        assertThat(staging.enabled()).isTrue();
        assertThat(staging.configured()).isFalse();
        assertThat(staging.overridden()).isTrue();
    }

    @Test
    void modules_matches_the_entries_of_modulesResponse() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "full"), Map.of());

        List<SystemCapabilitiesResponse.ModuleCapabilityResponse> modules = ModuleCapabilityMapper.modules(registry);

        assertThat(modules).isEqualTo(ModuleCapabilityMapper.modulesResponse(registry).modules());
    }
}
