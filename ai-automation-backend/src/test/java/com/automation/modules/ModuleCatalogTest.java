package com.automation.modules;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleCatalogTest {

    @Test
    void keysAreUniqueAndKebabCase() {
        List<String> keys = Arrays.stream(ModuleCatalog.values()).map(ModuleCatalog::key).toList();

        assertThat(keys).doesNotHaveDuplicates()
                .allSatisfy(k -> assertThat(k).matches("[a-z]+(-[a-z]+)*"));
    }

    @Test
    void kindFollowsTheTier() {
        for (ModuleCatalog m : ModuleCatalog.values()) {
            assertThat(m.kind()).as(m.key())
                    .isEqualTo(m.tier() == ModuleTier.HARDENING ? ModuleKind.HARDENING : ModuleKind.FEATURE);
        }
    }

    @Test
    void propertyAndEnvVarUseTheKindPrefix() {
        assertThat(ModuleCatalog.REMOTE_ACCESS.property()).isEqualTo("automation.modules.remote-access");
        assertThat(ModuleCatalog.REMOTE_ACCESS.envVar()).isEqualTo("AUTOMATION_MODULES_REMOTE_ACCESS");
        assertThat(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED.property())
                .isEqualTo("automation.hardening.require-sealed-for-unattended");
        assertThat(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED.envVar())
                .isEqualTo("AUTOMATION_HARDENING_REQUIRE_SEALED_FOR_UNATTENDED");
    }

    @Test
    void disabledMessageOffersTheSettingsUiOnlyForFeatureModules() {
        String feature = ModuleCatalog.CHAINS.disabledMessage("Chain execution");
        String hardening = ModuleCatalog.SEALING.disabledMessage("Sealing");

        assertThat(feature).startsWith("Chain execution requires module 'chains'")
                .contains("set automation.modules.chains=true and restart")
                .contains("Settings → Modules");
        assertThat(hardening).contains("set automation.hardening.sealing=true and restart")
                .doesNotContain("Settings");
    }

    @Test
    void byKeyIsExact() {
        assertThat(ModuleCatalog.byKey("remote-access")).contains(ModuleCatalog.REMOTE_ACCESS);
        assertThat(ModuleCatalog.byKey("REMOTE-ACCESS")).isEmpty();
        assertThat(ModuleCatalog.byKey("remote_access")).isEmpty();
        assertThat(ModuleCatalog.byKey("nope")).isEmpty();
    }

    @Test
    void byRelaxedKeyIgnoresCaseAndSeparatorsWithinOneKind() {
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.FEATURE, "remote.access")).contains(ModuleCatalog.REMOTE_ACCESS);
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.FEATURE, "REMOTE_ACCESS")).contains(ModuleCatalog.REMOTE_ACCESS);
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.FEATURE, "remoteaccess")).contains(ModuleCatalog.REMOTE_ACCESS);
        // Binder map keys of relaxed sources can arrive bracketed
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.FEATURE, "[remote-access]")).contains(ModuleCatalog.REMOTE_ACCESS);
    }

    @Test
    void byRelaxedKeyDoesNotCrossKinds() {
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.FEATURE, "sealing")).isEmpty();
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.HARDENING, "sealing")).contains(ModuleCatalog.SEALING);
        assertThat(ModuleCatalog.byRelaxedKey(ModuleKind.HARDENING, "chains")).isEmpty();
    }

    @Test
    void requiresResolvesToCatalogEntries() {
        assertThat(ModuleCatalog.WISHES.requires()).containsExactly(ModuleCatalog.CHAINS);
        assertThat(ModuleCatalog.DIALOG_IMPORT.requires()).containsExactly(ModuleCatalog.CONVERSION);
        assertThat(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED.requires()).containsExactly(ModuleCatalog.SEALING);
        assertThat(ModuleCatalog.CHAINS.requires()).isEmpty();
    }

    @Test
    void everyRequiresKeyResolvesAndTheGraphHasNoCycle() {
        for (ModuleCatalog m : ModuleCatalog.values()) {
            assertThat(reachable(m, new HashSet<>())).as("cycle through " + m.key()).doesNotContain(m);
        }
    }

    @Test
    void featureModulesNeverRequireAHardeningSwitch() {
        // a preset could otherwise enable a feature whose dependency only the startup file can switch on
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.kind() == ModuleKind.FEATURE)
                .forEach(m -> assertThat(m.requires()).as(m.key())
                        .allSatisfy(r -> assertThat(r.kind()).isEqualTo(ModuleKind.FEATURE)));
    }

    @Test
    void onlyStandardFeatureModulesDocumentDefaultOn() {
        Arrays.stream(ModuleCatalog.values())
                .filter(m -> m.kind() == ModuleKind.FEATURE)
                .forEach(m -> assertThat(m.defaultEnabled()).as(m.key()).isEqualTo(m.tier() == ModuleTier.STANDARD));
    }

    @Test
    void descriptionsAreNeverBlank() {
        assertThat(ModuleCatalog.values()).allSatisfy(m -> assertThat(m.description()).isNotBlank());
    }

    private static Set<ModuleCatalog> reachable(ModuleCatalog from, Set<ModuleCatalog> seen) {
        for (ModuleCatalog next : from.requires()) {
            if (seen.add(next)) {
                reachable(next, seen);
            }
        }
        return seen;
    }
}
