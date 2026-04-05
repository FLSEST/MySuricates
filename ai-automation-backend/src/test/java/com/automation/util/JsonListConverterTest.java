package com.automation.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonListConverterTest {

    private JsonListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JsonListConverter();
    }

    // --- convertToDatabaseColumn ---

    @Test
    void should_returnNull_when_attributeIsNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertThat(result).isNull();
    }

    @Test
    void should_returnNull_when_attributeIsEmptyList() {
        String result = converter.convertToDatabaseColumn(new ArrayList<>());

        assertThat(result).isNull();
    }

    @Test
    void should_serializeList_when_attributeHasStringEntries() {
        List<Object> list = List.of("alpha", "beta", "gamma");

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result).isEqualTo("[\"alpha\",\"beta\",\"gamma\"]");
    }

    @Test
    void should_serializeMixedTypes_when_attributeContainsStringsNumbersBooleans() {
        List<Object> list = new ArrayList<>();
        list.add("text");
        list.add(42);
        list.add(true);
        list.add(3.14);

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result)
                .contains("\"text\"")
                .contains("42")
                .contains("true")
                .contains("3.14");
    }

    @Test
    void should_serializeNestedObjects_when_attributeContainsMaps() {
        List<Object> list = List.of(
                Map.of("name", "workflow-1"),
                Map.of("name", "workflow-2")
        );

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result)
                .contains("\"name\"")
                .contains("\"workflow-1\"")
                .contains("\"workflow-2\"");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyList_when_dbDataIsNull() {
        List<Object> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsEmpty() {
        List<Object> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsBlank() {
        List<Object> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeJson_when_dbDataIsValidJsonArray() {
        String json = "[\"one\",\"two\",\"three\"]";

        List<Object> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly("one", "two", "three");
    }

    @Test
    void should_deserializeMixedTypes_when_dbDataContainsStringsNumbersBooleans() {
        String json = "[\"hello\",42,true,1.5]";

        List<Object> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly("hello", 42, true, 1.5);
    }

    @Test
    void should_deserializeNestedObjects_when_dbDataContainsMaps() {
        String json = "[{\"key\":\"value\"},{\"nested\":{\"deep\":true}}]";

        List<Object> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) result.get(0);
        assertThat(first).containsEntry("key", "value");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) result.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) second.get("nested");
        assertThat(nested).containsEntry("deep", true);
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "[broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to list");
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_typicalListData() {
        List<Object> original = new ArrayList<>();
        original.add("backend");
        original.add("testing");
        original.add(Map.of("enabled", true, "count", 5));
        original.add(42);

        String json = converter.convertToDatabaseColumn(original);
        List<Object> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(4);
        assertThat(restored.get(0)).isEqualTo("backend");
        assertThat(restored.get(1)).isEqualTo("testing");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) restored.get(2);
        assertThat(map).containsEntry("enabled", true).containsEntry("count", 5);
        assertThat(restored.get(3)).isEqualTo(42);
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        List<Object> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyList_when_emptyListInput() {
        String json = converter.convertToDatabaseColumn(new ArrayList<>());
        List<Object> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }
}
