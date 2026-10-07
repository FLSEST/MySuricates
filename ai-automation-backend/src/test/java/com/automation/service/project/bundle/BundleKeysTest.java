package com.automation.service.project.bundle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BundleKeysTest {

    @Test
    void slug_lowerCasesAndTransliteratesUmlautsAndAccents() {
        assertThat(BundleKeys.slug("Größe Übersicht", "x")).isEqualTo("groesse-uebersicht");
        assertThat(BundleKeys.slug("Café Crème", "x")).isEqualTo("cafe-creme");
    }

    @Test
    void slug_collapsesEverythingElseIntoSingleDashesAndTrimsThem() {
        assertThat(BundleKeys.slug("  --Code  Quality!! (v2)--  ", "x")).isEqualTo("code-quality-v2");
    }

    @Test
    void slug_fallsBackForNullAndForNamesWithoutAnyUsableCharacter() {
        assertThat(BundleKeys.slug(null, "workflow")).isEqualTo("workflow");
        assertThat(BundleKeys.slug("", "workflow")).isEqualTo("workflow");
        assertThat(BundleKeys.slug("!!! ???", "workflow")).isEqualTo("workflow");
    }

    @Test
    void slug_isCutToSixtyCharactersWithoutTrailingDash() {
        String slug = BundleKeys.slug("a".repeat(59) + " bc", "x");

        assertThat(slug).isEqualTo("a".repeat(59));
        assertThat(BundleKeys.slug("b".repeat(80), "x")).hasSize(60);
    }

    @Test
    void next_makesRepeatedNamesUniqueWithCounterSuffix() {
        BundleKeys keys = new BundleKeys("workflow");

        assertThat(keys.next("Tests")).isEqualTo("tests");
        assertThat(keys.next("Tests")).isEqualTo("tests-2");
        assertThat(keys.next("tests")).isEqualTo("tests-3");
        assertThat(keys.next("Other")).isEqualTo("other");
    }

    @Test
    void next_alsoAvoidsCollisionsWithANameThatLooksLikeASuffixedKey() {
        BundleKeys keys = new BundleKeys("workflow");

        assertThat(keys.next("a")).isEqualTo("a");
        assertThat(keys.next("a")).isEqualTo("a-2");
        assertThat(keys.next("a-2")).isEqualTo("a-2-2");
    }

    @Test
    void next_usesTheFallbackAsBaseForNamelessEntries() {
        BundleKeys keys = new BundleKeys("chain");

        assertThat(keys.next(null)).isEqualTo("chain");
        assertThat(keys.next("???")).isEqualTo("chain-2");
    }
}
