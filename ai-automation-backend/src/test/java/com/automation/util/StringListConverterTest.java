package com.automation.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StringListConverterTest {

    private StringListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringListConverter();
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
    void should_serializeSingleString_when_attributeHasOneEntry() {
        List<String> list = List.of("hello");

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result).isEqualTo("[\"hello\"]");
    }

    @Test
    void should_serializeMultipleStrings_when_attributeHasMultipleEntries() {
        List<String> list = List.of("alpha", "beta", "gamma");

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result).isEqualTo("[\"alpha\",\"beta\",\"gamma\"]");
    }

    @Test
    void should_serializeUnicode_when_attributeContainsUnicodeStrings() {
        List<String> list = List.of("Hallo Welt", "日本語", "emoji: \uD83D\uDE80");

        String result = converter.convertToDatabaseColumn(list);

        assertThat(result)
                .contains("Hallo Welt")
                .contains("日本語")
                .contains("\uD83D\uDE80");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyList_when_dbDataIsNull() {
        List<String> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsEmpty() {
        List<String> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsBlank() {
        List<String> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeSingleString_when_dbDataHasOneElement() {
        String json = "[\"only\"]";

        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly("only");
    }

    @Test
    void should_deserializeMultipleStrings_when_dbDataHasMultipleElements() {
        String json = "[\"one\",\"two\",\"three\"]";

        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly("one", "two", "three");
    }

    @Test
    void should_deserializeUnicode_when_dbDataContainsUnicodeStrings() {
        String json = "[\"Hallo Welt\",\"日本語\",\"emoji: \uD83D\uDE80\"]";

        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(result).containsExactly("Hallo Welt", "日本語", "emoji: \uD83D\uDE80");
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "[broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to string list");
    }

    @Test
    void should_throwException_when_dbDataIsJsonObject() {
        String jsonObject = "{\"key\":\"value\"}";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(jsonObject))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to string list");
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_typicalStringList() {
        List<String> original = List.of("backend", "testing", "workflow");

        String json = converter.convertToDatabaseColumn(original);
        List<String> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).containsExactlyElementsOf(original);
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyList_when_emptyListInput() {
        String json = converter.convertToDatabaseColumn(new ArrayList<>());
        List<String> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    // --- Mutable list ---

    @Test
    void should_returnMutableList_when_deserializing() {
        String json = "[\"a\",\"b\"]";

        List<String> result = converter.convertToEntityAttribute(json);
        result.add("c");

        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    void should_returnMutableList_when_dbDataIsNull() {
        List<String> result = converter.convertToEntityAttribute(null);
        result.add("added");

        assertThat(result).containsExactly("added");
    }
}
