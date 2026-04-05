package com.automation.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonStringMapConverterTest {

    private JsonStringMapConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JsonStringMapConverter();
    }

    // --- convertToDatabaseColumn ---

    @Test
    void should_returnNull_when_attributeIsNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertThat(result).isNull();
    }

    @Test
    void should_returnNull_when_attributeIsEmptyMap() {
        String result = converter.convertToDatabaseColumn(new HashMap<>());

        assertThat(result).isNull();
    }

    @Test
    void should_serializeMap_when_attributeHasSingleEntry() {
        Map<String, String> map = Map.of("de", "Hallo Welt");

        String result = converter.convertToDatabaseColumn(map);

        assertThat(result).isEqualTo("{\"de\":\"Hallo Welt\"}");
    }

    @Test
    void should_serializeMap_when_attributeHasMultipleEntries() {
        Map<String, String> map = new HashMap<>();
        map.put("de", "Workflow erstellen");
        map.put("en", "Create workflow");

        String result = converter.convertToDatabaseColumn(map);

        assertThat(result)
                .contains("\"de\":\"Workflow erstellen\"")
                .contains("\"en\":\"Create workflow\"");
    }

    @Test
    void should_serializeUnicode_when_attributeContainsUnicodeCharacters() {
        Map<String, String> map = Map.of(
                "ja", "ワークフロー",
                "emoji", "🚀 Launch"
        );

        String result = converter.convertToDatabaseColumn(map);

        assertThat(result)
                .contains("ワークフロー")
                .contains("🚀 Launch");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyMap_when_dbDataIsNull() {
        Map<String, String> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyMap_when_dbDataIsEmpty() {
        Map<String, String> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyMap_when_dbDataIsBlank() {
        Map<String, String> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeJson_when_dbDataIsValidJson() {
        String json = "{\"de\":\"Name\",\"en\":\"Name\"}";

        Map<String, String> result = converter.convertToEntityAttribute(json);

        assertThat(result)
                .containsEntry("de", "Name")
                .containsEntry("en", "Name");
    }

    @Test
    void should_deserializeUnicode_when_dbDataContainsUnicodeCharacters() {
        String json = "{\"ja\":\"ワークフロー\",\"emoji\":\"🚀 Launch\"}";

        Map<String, String> result = converter.convertToEntityAttribute(json);

        assertThat(result)
                .containsEntry("ja", "ワークフロー")
                .containsEntry("emoji", "🚀 Launch");
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "{broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to map");
    }

    @Test
    void should_throwException_when_dbDataIsJsonArray() {
        String jsonArray = "[\"not\",\"a\",\"map\"]";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(jsonArray))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_typicalI18nData() {
        Map<String, String> original = new HashMap<>();
        original.put("de", "Täglicher Bericht erstellen");
        original.put("en", "Create daily report");
        original.put("fr", "Créer un rapport quotidien");

        String json = converter.convertToDatabaseColumn(original);
        Map<String, String> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        Map<String, String> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyMap_when_emptyMapInput() {
        String json = converter.convertToDatabaseColumn(new HashMap<>());
        Map<String, String> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_returnMutableMap_when_deserializing() {
        String json = "{\"key\":\"value\"}";

        Map<String, String> result = converter.convertToEntityAttribute(json);
        result.put("newKey", "newValue");

        assertThat(result).containsEntry("newKey", "newValue");
    }
}
