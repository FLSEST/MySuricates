package com.automation.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMapConverterTest {

    private JsonMapConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JsonMapConverter();
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
    void should_serializeMap_when_attributeHasEntries() {
        Map<String, Object> map = Map.of("key", "value");

        String result = converter.convertToDatabaseColumn(map);

        assertThat(result).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void should_serializeNestedMap_when_attributeContainsNestedStructure() {
        Map<String, Object> inner = Map.of("nested", "data");
        Map<String, Object> outer = new HashMap<>();
        outer.put("level1", inner);

        String result = converter.convertToDatabaseColumn(outer);

        assertThat(result).contains("\"level1\"").contains("\"nested\"").contains("\"data\"");
    }

    @Test
    void should_serializeMixedTypes_when_attributeContainsStringsNumbersBooleans() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "test-workflow");
        metadata.put("retryCount", 3);
        metadata.put("enabled", true);
        metadata.put("score", 0.95);

        String result = converter.convertToDatabaseColumn(metadata);

        assertThat(result)
                .contains("\"name\":\"test-workflow\"")
                .contains("\"retryCount\":3")
                .contains("\"enabled\":true")
                .contains("\"score\":0.95");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyMap_when_dbDataIsNull() {
        Map<String, Object> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyMap_when_dbDataIsEmpty() {
        Map<String, Object> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyMap_when_dbDataIsBlank() {
        Map<String, Object> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeJson_when_dbDataIsValidJson() {
        String json = "{\"key\":\"value\"}";

        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsEntry("key", "value");
    }

    @Test
    void should_deserializeNestedJson_when_dbDataContainsNestedObjects() {
        String json = "{\"outer\":{\"inner\":\"deep\"}}";

        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsKey("outer");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("outer");
        assertThat(nested).containsEntry("inner", "deep");
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "{broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to map");
    }

    @Test
    void should_deserializeMixedTypes_when_dbDataContainsStringsNumbersBooleans() {
        String json = "{\"name\":\"workflow\",\"count\":42,\"active\":true,\"ratio\":1.5}";

        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertThat(result)
                .containsEntry("name", "workflow")
                .containsEntry("count", 42)
                .containsEntry("active", true)
                .containsEntry("ratio", 1.5);
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_typicalEntityMetadata() {
        Map<String, Object> original = new HashMap<>();
        original.put("gate_status", "GREEN");
        original.put("gate_category", "quality");
        original.put("autoTodo", Map.of("enabled", true, "mode", "track"));
        original.put("tags", List.of("backend", "testing"));

        String json = converter.convertToDatabaseColumn(original);
        Map<String, Object> restored = converter.convertToEntityAttribute(json);

        assertThat(restored)
                .containsEntry("gate_status", "GREEN")
                .containsEntry("gate_category", "quality");
        @SuppressWarnings("unchecked")
        Map<String, Object> autoTodo = (Map<String, Object>) restored.get("autoTodo");
        assertThat(autoTodo).containsEntry("enabled", true).containsEntry("mode", "track");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) restored.get("tags");
        assertThat(tags).containsExactly("backend", "testing");
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyMap_when_emptyMapInput() {
        String json = converter.convertToDatabaseColumn(new HashMap<>());
        Map<String, Object> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }
}
