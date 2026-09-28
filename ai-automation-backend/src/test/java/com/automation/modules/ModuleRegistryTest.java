package com.automation.modules;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.automation.exception.ErrorCode;
import com.automation.exception.ModuleChangeException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleRegistryTest {

    @Test
    void defaultPresetIsCoreWithStandardModulesAndDefaultOnHardening() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of(), Map.of());

        assertThat(registry.preset()).isEqualTo(ModulePreset.CORE);
        assertThat(Arrays.stream(ModuleCatalog.values()).filter(registry::isEnabled))
                .containsExactly(ModuleCatalog.DASHBOARD_REPORT, ModuleCatalog.PROVENANCE, ModuleCatalog.PRIVATE_NETWORK_GUARD);
    }

    /** "Only" as in: of the tiers the preset adds — the STANDARD tier is on here as in every preset. */
    @Test
    void researchPresetAddsTheResearchTierButNoAdvancedModule() {
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

        assertThat(registry.summary()).isEqualTo("preset=core, enabled=[dashboard-report, chains, provenance, private-network-guard]");
    }

    // --- automation.modules.ui-editable ---------------------------------------------

    private static ModuleRegistry editable(Map<String, String> modules) {
        Map<String, String> entries = new java.util.HashMap<>(modules);
        entries.put("ui-editable", "true");
        return ModuleRegistry.of(entries, Map.of());
    }

    @Test
    void uiEditableIsOffByDefaultAndNotAModuleName() {
        assertThat(ModuleRegistry.of(Map.of(), Map.of()).uiEditable()).isFalse();
        assertThat(ModuleRegistry.of(Map.of("ui-editable", "true"), Map.of()).uiEditable()).isTrue();
        assertThatThrownBy(() -> ModuleRegistry.of(Map.of("ui-editable", "yes"), Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("automation.modules.ui-editable");
    }

    @Test
    void uiEditableBindsFromTheEnvironmentVariable() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("AUTOMATION_MODULES_UI_EDITABLE", "true")));

        assertThat(ModuleRegistry.fromEnvironment(env).uiEditable()).isTrue();
    }

    @Test
    void uiOverridesAreRefusedUnlessUiEditable() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of(), Map.of());

        assertThatThrownBy(() -> registry.applyUiOverrides(Map.of(ModuleCatalog.WEBHOOKS, true)))
                .isInstanceOf(ModuleChangeException.class)
                .satisfies(e -> assertThat(((ModuleChangeException) e).getCode()).isEqualTo(ErrorCode.MODULES_NOT_EDITABLE))
                .hasMessageContaining("automation.modules.ui-editable=true");
        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isFalse();
    }

    @Test
    void uiOverrideWinsOverConfigurationAndTakesEffectImmediately() {
        ModuleRegistry registry = editable(Map.of("preset", "full"));

        registry.applyUiOverrides(Map.of(ModuleCatalog.WEBHOOKS, false, ModuleCatalog.CHAINS, true));

        assertThat(registry.isEnabled(ModuleCatalog.WEBHOOKS)).isFalse();
        assertThat(registry.status(ModuleCatalog.WEBHOOKS))
                .isEqualTo(new ModuleRegistry.ModuleStatus(ModuleCatalog.WEBHOOKS, false, true, true));
        // equal to the configured value ⇒ no override kept
        assertThat(registry.uiOverrides()).containsOnlyKeys(ModuleCatalog.WEBHOOKS);
        assertThat(registry.status(ModuleCatalog.CHAINS).overridden()).isFalse();
    }

    @Test
    void hardeningSwitchesAreNeverUiEditable() {
        ModuleRegistry registry = editable(Map.of());

        assertThatThrownBy(() -> registry.applyUiOverrides(Map.of(ModuleCatalog.SEALING, true)))
                .isInstanceOf(ModuleChangeException.class)
                .satisfies(e -> assertThat(((ModuleChangeException) e).getCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT))
                .hasMessageContaining("automation.hardening.sealing");
    }

    @Test
    void dependenciesAreCheckedAtRuntimeAndNothingIsApplied() {
        ModuleRegistry registry = editable(Map.of());

        assertThatThrownBy(() -> registry.applyUiOverrides(Map.of(ModuleCatalog.WISHES, true)))
                .isInstanceOf(ModuleChangeException.class)
                .satisfies(e -> assertThat(((ModuleChangeException) e).getCode()).isEqualTo(ErrorCode.STATE_CONFLICT))
                .hasMessageContaining("enable 'chains' first");
        assertThat(registry.isEnabled(ModuleCatalog.WISHES)).isFalse();

        registry.applyUiOverrides(Map.of(ModuleCatalog.CHAINS, true, ModuleCatalog.WISHES, true));
        assertThatThrownBy(() -> registry.applyUiOverrides(Map.of(ModuleCatalog.CHAINS, false, ModuleCatalog.WISHES, true)))
                .hasMessageContaining("disable 'wishes'");
        assertThat(registry.isEnabled(ModuleCatalog.CHAINS)).isTrue();
    }

    @Test
    void summaryShowsUiOverridesWhenEditable() {
        ModuleRegistry registry = editable(Map.of());
        registry.applyUiOverrides(Map.of(ModuleCatalog.CHAINS, true));

        assertThat(registry.summary()).isEqualTo("preset=core, ui-editable=true, ui-overrides=[chains=true], enabled=[dashboard-report, chains, provenance, private-network-guard]");
    }

    @Test
    void allEnabledTurnsOnEverything() {
        ModuleRegistry registry = ModuleRegistry.allEnabled();

        assertThat(registry.describe()).allMatch(ModuleRegistry.ModuleStatus::enabled);
    }

    @Test
    void standardTierIsOnInEveryPreset() {
        for (ModulePreset preset : ModulePreset.values()) {
            ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", preset.key()), Map.of());
            for (ModuleCatalog module : ModuleCatalog.values()) {
                if (module.tier() == ModuleTier.STANDARD) {
                    assertThat(registry.isEnabled(module))
                            .as("%s under preset %s", module.key(), preset.key())
                            .isTrue();
                }
            }
        }
    }

    /**
     * The invariant the STANDARD tier owes the public {@code core} preset: it is on everywhere, so a
     * module of this tier must be as harmless as the core itself. Written as a rule over the whole
     * catalog, not as an assertion about today's only member — the danger is a <em>future</em>
     * module that quietly ships network, credential or host access to every instance, including the
     * ones that deliberately chose the smallest surface. Such a module belongs in ADVANCED.
     */
    @Test
    void everyStandardTierModuleCarriesNoRisk() {
        List<ModuleCatalog> standard = Arrays.stream(ModuleCatalog.values())
                .filter(module -> module.tier() == ModuleTier.STANDARD)
                .toList();

        assertThat(standard).as("no STANDARD module left — then this rule guards nothing").isNotEmpty();
        assertThat(standard).allSatisfy(module -> assertThat(module.risks())
                .as("module '%s' has tier STANDARD, so it runs in the public core preset; a module "
                        + "with NETWORK, CREDENTIALS or HOST_ACCESS risk must be ADVANCED instead", module.key())
                .isEmpty());
    }

    @Test
    void aStandardModuleCanStillBeSwitchedOff() {
        ModuleRegistry registry = ModuleRegistry.of(
                Map.of("preset", "core", "dashboard-report", "false"), Map.of());

        assertThat(registry.isEnabled(ModuleCatalog.DASHBOARD_REPORT)).isFalse();
        assertThat(registry.status(ModuleCatalog.DASHBOARD_REPORT).configured()).isFalse();
    }

    @Test
    void dashboardReportIsAStandardFeatureModuleWithoutRisksOrRequirements() {
        assertThat(ModuleCatalog.DASHBOARD_REPORT.kind()).isEqualTo(ModuleKind.FEATURE);
        assertThat(ModuleCatalog.DASHBOARD_REPORT.tier()).isEqualTo(ModuleTier.STANDARD);
        assertThat(ModuleCatalog.DASHBOARD_REPORT.risks()).isEmpty();
        assertThat(ModuleCatalog.DASHBOARD_REPORT.requires()).isEmpty();
        assertThat(ModuleCatalog.DASHBOARD_REPORT.property()).isEqualTo("automation.modules.dashboard-report");
    }
}
