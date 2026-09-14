package com.automation.modules;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRegistryTest {

    @Test
    void defaultPresetIsCoreWithOnlyProvenanceOn() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of(), Map.of());

        assertThat(registry.preset()).isEqualTo(ModulePreset.CORE);
        assertThat(Arrays.stream(ModuleCatalog.values()).filter(registry::isEnabled))
                .containsExactly(ModuleCatalog.PROVENANCE);
    }

    @Test
    void researchPresetEnablesResearchTierOnly() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "research"), Map.of());

        assertThat(registry.isEnabled(ModuleCatalog.CHAINS)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.AUDIT)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isFalse();
        assertThat(registry.isEnabled(ModuleCatalog.WISHES)).isFalse();
    }

    @Test
    void fullPresetEnablesEveryFeatureButLeavesHardeningDefaults() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "FULL"), Map.of());

        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.kind() == ModuleKind.FEATURE)
                .forEach(m -> assertThat(registry.isEnabled(m)).as(m.key()).isTrue());
        assertThat(registry.isEnabled(ModuleCatalog.PROVENANCE)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.SEALING)).isFalse();
    }

    @Test
    void overrideBeatsPresetInBothDirections() {
        ModuleRegistry registry = ModuleRegistry.of(
                Map.of("preset", "research", "chains", "false", "webhooks", "true"), Map.of());

        assertThat(registry.isEnabled(ModuleCatalog.CHAINS)).isFalse();
        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isTrue();
    }

    @Test
    void hardeningOverridesAreIndependentOfPreset() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of(), Map.of("sealing", "true", "provenance", "true"));

        assertThat(registry.isEnabled(ModuleCatalog.SEALING)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.CHAINS)).isFalse();
    }

    @Test
    void unknownModuleFailsWithKnownNames() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("chainz", "true"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chainz")
                .hasMessageContaining("chains");
    }

    @Test
    void hardeningKeyUnderModulesPrefixIsUnknown() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("sealing", "true"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("automation.modules");
    }

    @Test
    void unknownPresetFails() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("preset", "everything"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core, research, full");
    }

    @Test
    void nonBooleanValueFails() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("chains", "yes"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("automation.modules.chains must be true or false");
    }

    @Test
    void unmetRequiresFailsAndNamesTheProperty() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("wishes", "true"), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires 'chains'")
                .hasMessageContaining("automation.modules.chains=true");
    }

    @Test
    void sealingWithoutProvenanceFails() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of(), Map.of("sealing", "true", "provenance", "false")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("automation.hardening.provenance=true");
    }

    @Test
    void webhookSignaturesRequireWebhooksFeatureModule() {
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of(), Map.of("webhook-signatures", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("automation.modules.webhooks=true");
    }

    @Test
    void bindsYamlStylePropertiesFromEnvironment() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "automation.modules.preset", "core",
                "automation.modules.remote-access", "true",
                "automation.hardening.sealing", "true")));

        ModuleRegistry registry = ModuleRegistry.fromEnvironment(env);

        assertThat(registry.isEnabled(ModuleCatalog.REMOTE_ACCESS)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.SEALING)).isTrue();
    }

    @Test
    void bindsEnvironmentVariablesWithRelaxedNames() {
        StandardEnvironment env = new StandardEnvironment();
        // The name matters: Spring Boot only applies relaxed env-var mapping to "systemEnvironment".
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "AUTOMATION_MODULES_PRESET", "research",
                "AUTOMATION_MODULES_REMOTE_ACCESS", "true",
                "AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED", "true",
                "AUTOMATION_HARDENING_SEALING", "true")));

        ModuleRegistry registry = ModuleRegistry.fromEnvironment(env);

        assertThat(registry.preset()).isEqualTo(ModulePreset.RESEARCH);
        assertThat(registry.isEnabled(ModuleCatalog.REMOTE_ACCESS)).isTrue();
        assertThat(registry.isEnabled(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED)).isTrue();
    }

    @Test
    void catalogKeysPropertiesAndEnvVarsAreConsistent() {
        assertThat(ModuleCatalog.REMOTE_ACCESS.property()).isEqualTo("automation.modules.remote-access");
        assertThat(ModuleCatalog.REMOTE_ACCESS.envVar()).isEqualTo("AUTOMATION_MODULES_REMOTE_ACCESS");
        assertThat(ModuleCatalog.WEBHOOK_SIGNATURES.envVar()).isEqualTo("AUTOMATION_HARDENING_WEBHOOK_SIGNATURES");
        Arrays.stream(ModuleCatalog.values())
                .forEach(m -> assertThat(ModuleCatalog.byKey(m.key())).as(m.key()).contains(m));
        // requires() resolves for every entry (no dangling keys)
        Arrays.stream(ModuleCatalog.values()).forEach(ModuleCatalog::requires);
    }

    @Test
    void summaryListsPresetAndEnabledModules() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("chains", "true"), Map.of());

        assertThat(registry.summary()).isEqualTo("preset=core, enabled=[chains, provenance]");
    }

    @Test
    void allEnabledTurnsOnEverything() {
        ModuleRegistry registry = ModuleRegistry.allEnabled();

        assertThat(registry.describe()).allMatch(ModuleRegistry.ModuleStatus::enabled);
    }
}
