package com.automation.util;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalizedJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LOCALIZED = """
            {
              "id": "example-approval",
              "i18n": ["de", "en"],
              "name": {"de": "Beispiel", "en": "Example"},
              "description": {"de": "Beschreibung", "en": "Description"},
              "instructions": {"de": "Arbeite gründlich.", "en": "Work carefully."},
              "blocks": [
                {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
                {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
                 "config": {
                   "question": {"de": "{{ block.messen.output }} – weiter?", "en": "{{ block.measure.output }} – continue?"},
                   "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}],
                   "on_no": "fail"
                 }},
                {"type": "foreach", "config": {"blocks": [
                  {"type": "simple", "name": "inner", "config": {"prompt": {"de": "Fasse zusammen", "en": "Summarize"}}}
                ]}}
              ]
            }""";

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void normalize_takesTheLanguageAndFallsBackToEnglish() {
        assertThat(LocalizedJson.normalize("de")).isEqualTo("de");
        assertThat(LocalizedJson.normalize("de-DE")).isEqualTo("de");
        assertThat(LocalizedJson.normalize("DE_ch")).isEqualTo("de");
        assertThat(LocalizedJson.normalize("fr")).isEqualTo("en");
        assertThat(LocalizedJson.normalize(null)).isEqualTo("en");
        assertThat(LocalizedJson.normalize(" ")).isEqualTo("en");
    }

    @Test
    void resolveDefinition_german_resolvesBlocksAndInstructions_keepsNameAndDescriptionMaps() throws Exception {
        JsonNode de = LocalizedJson.resolveDefinition(json(LOCALIZED), "de");

        assertThat(de.at("/blocks/0/name").asText()).isEqualTo("messen");
        assertThat(de.at("/blocks/1/config/question").asText()).isEqualTo("{{ block.messen.output }} – weiter?");
        assertThat(de.at("/blocks/1/config/options/0").asText()).isEqualTo("Ja");
        assertThat(de.at("/blocks/1/config/options/1").asText()).isEqualTo("Nein");
        assertThat(de.at("/blocks/2/config/blocks/0/config/prompt").asText()).isEqualTo("Fasse zusammen");
        assertThat(de.get("instructions").asText()).isEqualTo("Arbeite gründlich.");
        assertThat(de.at("/blocks/0/config/command").asText()).isEqualTo("date");
        assertThat(de.get("name").isObject()).isTrue();
        assertThat(de.get("description").isObject()).isTrue();
    }

    @Test
    void resolveDefinition_unknownLocale_fallsBackToEnglish() throws Exception {
        JsonNode fr = LocalizedJson.resolveDefinition(json(LOCALIZED), "fr");

        assertThat(fr.at("/blocks/0/name").asText()).isEqualTo("measure");
        assertThat(fr.at("/blocks/1/config/options/0").asText()).isEqualTo("Yes");
    }

    @Test
    void resolveDefinition_doesNotMutateItsInput() throws Exception {
        JsonNode root = json(LOCALIZED);
        String before = root.toString();

        LocalizedJson.resolveDefinition(root, "de");

        assertThat(root.toString()).isEqualTo(before);
    }

    @Test
    void fileWithoutMarker_staysUntouched_evenWithDeEnDataMaps() throws Exception {
        String plain = """
                {"name": {"de": "A", "en": "B"},
                 "blocks": [{"type": "shell", "config": {"mapping": {"de": "Deutsch", "en": "English"}}}]}""";

        JsonNode resolved = LocalizedJson.resolveDefinition(json(plain), "de");

        assertThat(resolved).isEqualTo(json(plain));
        assertThat(LocalizedJson.localizedSource(json(plain))).isNull();
    }

    @Test
    void onlyExactLocaleKeysWithStringValuesAreLocalizedText() throws Exception {
        List<String> locales = List.of("de", "en");

        assertThat(LocalizedJson.isLocalizedText(json("{\"de\":\"a\",\"en\":\"b\"}"), locales)).isTrue();
        assertThat(LocalizedJson.isLocalizedText(json("{\"de\":\"a\"}"), locales)).isFalse();
        assertThat(LocalizedJson.isLocalizedText(json("{\"de\":\"a\",\"en\":\"b\",\"fr\":\"c\"}"), locales)).isFalse();
        assertThat(LocalizedJson.isLocalizedText(json("{\"de\":\"a\",\"en\":1}"), locales)).isFalse();
    }

    @Test
    void storedSource_resolvesToBlocksAndInstructionsPerLanguage() throws Exception {
        String source = LocalizedJson.localizedSource(json(LOCALIZED));
        List<BlockDefinition> fallback = List.of(BlockDefinition.builder().type("shell").name("fallback").build());

        List<BlockDefinition> de = LocalizedJson.blocksFor(source, "de", fallback);
        List<BlockDefinition> en = LocalizedJson.blocksFor(source, "en", fallback);

        assertThat(de).extracting(BlockDefinition::getName).containsExactly("messen", "freigabe", null);
        assertThat(en).extracting(BlockDefinition::getName).containsExactly("measure", "approval", null);
        assertThat(de.get(1).getConfig().get("options")).isEqualTo(List.of("Ja", "Nein"));
        assertThat(LocalizedJson.instructionsFor(source, "de", "x")).isEqualTo("Arbeite gründlich.");
        assertThat(LocalizedJson.instructionsFor(source, "en", "x")).isEqualTo("Work carefully.");
    }

    @Test
    void missingOrBrokenSource_returnsTheFallback() {
        List<BlockDefinition> fallback = List.of(BlockDefinition.builder().type("shell").build());

        assertThat(LocalizedJson.blocksFor(null, "de", fallback)).isSameAs(fallback);
        assertThat(LocalizedJson.blocksFor("{not json", "de", fallback)).isSameAs(fallback);
        assertThat(LocalizedJson.instructionsFor(null, "de", "fallback")).isEqualTo("fallback");
    }

    @Test
    void pick_prefersLocaleThenEnglishThenGerman() {
        assertThat(LocalizedJson.pick(Map.of("de", "Hallo", "en", "Hello"), "de")).isEqualTo("Hallo");
        assertThat(LocalizedJson.pick(Map.of("de", "Hallo", "en", "Hello"), "fr")).isEqualTo("Hello");
        assertThat(LocalizedJson.pick(Map.of("de", "Hallo"), "en")).isEqualTo("Hallo");
        assertThat(LocalizedJson.pick(Map.of(), "en")).isNull();
    }
}
