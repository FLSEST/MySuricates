package com.automation.util;

import com.automation.entity.ChainEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainEntryListConverterTest {

    private ChainEntryListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ChainEntryListConverter();
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
    void should_serializeSingleEntry_when_attributeHasOneEntry() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-001")
                .label("First Step")
                .sortOrder(0)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result)
                .contains("\"workflowId\":\"wf-001\"")
                .contains("\"label\":\"First Step\"")
                .contains("\"sortOrder\":0");
    }

    @Test
    void should_serializeMultipleEntries_when_attributeHasMultipleEntries() {
        ChainEntry entry1 = ChainEntry.builder()
                .workflowId("wf-001")
                .label("Step 1")
                .sortOrder(0)
                .build();
        ChainEntry entry2 = ChainEntry.builder()
                .workflowId("wf-002")
                .label("Step 2")
                .sortOrder(1)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry1, entry2));

        assertThat(result)
                .startsWith("[")
                .endsWith("]")
                .contains("\"wf-001\"")
                .contains("\"wf-002\"")
                .contains("\"Step 1\"")
                .contains("\"Step 2\"");
    }

    @Test
    void should_serializeInputContext_when_entryHasInputContext() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-001")
                .label("With Context")
                .sortOrder(0)
                .inputContext(Map.of("key1", "value1", "key2", 42))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result)
                .contains("\"inputContext\"")
                .contains("\"key1\"")
                .contains("\"value1\"")
                .contains("42");
    }

    @Test
    void should_serializeStopOnFailureDefault_when_notExplicitlySet() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-001")
                .sortOrder(0)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result).contains("\"stopOnFailure\":true");
    }

    @Test
    void should_serializeStopOnFailureFalse_when_explicitlySetToFalse() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-001")
                .sortOrder(0)
                .stopOnFailure(false)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result).contains("\"stopOnFailure\":false");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyList_when_dbDataIsNull() {
        List<ChainEntry> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsEmpty() {
        List<ChainEntry> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsBlank() {
        List<ChainEntry> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeSingleEntry_when_dbDataHasOneEntry() {
        String json = "[{\"workflowId\":\"wf-001\",\"label\":\"Step 1\",\"sortOrder\":0,\"stopOnFailure\":true,\"inputContext\":{}}]";

        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(result.get(0).getLabel()).isEqualTo("Step 1");
        assertThat(result.get(0).getSortOrder()).isEqualTo(0);
        assertThat(result.get(0).isStopOnFailure()).isTrue();
        assertThat(result.get(0).getInputContext()).isEmpty();
    }

    @Test
    void should_deserializeMultipleEntries_when_dbDataHasMultipleEntries() {
        String json = "[{\"workflowId\":\"wf-001\",\"label\":\"A\",\"sortOrder\":0,\"stopOnFailure\":true,\"inputContext\":{}}," +
                      "{\"workflowId\":\"wf-002\",\"label\":\"B\",\"sortOrder\":1,\"stopOnFailure\":false,\"inputContext\":{\"env\":\"prod\"}}]";

        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(result.get(1).getWorkflowId()).isEqualTo("wf-002");
        assertThat(result.get(1).isStopOnFailure()).isFalse();
        assertThat(result.get(1).getInputContext()).containsEntry("env", "prod");
    }

    @Test
    void should_deserializeInputContext_when_dbDataContainsContextMap() {
        String json = "[{\"workflowId\":\"wf-001\",\"label\":\"ctx\",\"sortOrder\":0,\"stopOnFailure\":true,\"inputContext\":{\"name\":\"test\",\"count\":5,\"active\":true}}]";

        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getInputContext())
                .containsEntry("name", "test")
                .containsEntry("count", 5)
                .containsEntry("active", true);
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "[broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to chain entries");
    }

    @Test
    void should_throwException_when_dbDataIsJsonObjectInsteadOfArray() {
        String jsonObject = "{\"workflowId\":\"wf-001\"}";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(jsonObject))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_throwException_when_dbDataIsPlainString() {
        String plainString = "\"just a string\"";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(plainString))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_singleEntry() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-roundtrip")
                .label("Roundtrip")
                .sortOrder(0)
                .inputContext(Map.of("key", "value"))
                .build();

        String json = converter.convertToDatabaseColumn(List.of(entry));
        List<ChainEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getWorkflowId()).isEqualTo("wf-roundtrip");
        assertThat(restored.get(0).getLabel()).isEqualTo("Roundtrip");
        assertThat(restored.get(0).getSortOrder()).isEqualTo(0);
        assertThat(restored.get(0).isStopOnFailure()).isTrue();
        assertThat(restored.get(0).getInputContext()).containsEntry("key", "value");
    }

    @Test
    void should_roundtrip_when_multipleEntriesWithAllFields() {
        ChainEntry entry1 = ChainEntry.builder()
                .workflowId("wf-001")
                .label("First")
                .sortOrder(0)
                .stopOnFailure(true)
                .inputContext(Map.of("env", "dev"))
                .build();
        ChainEntry entry2 = ChainEntry.builder()
                .workflowId("wf-002")
                .label("Second")
                .sortOrder(1)
                .stopOnFailure(false)
                .inputContext(Map.of("batch", 10))
                .build();
        List<ChainEntry> original = new ArrayList<>();
        original.add(entry1);
        original.add(entry2);

        String json = converter.convertToDatabaseColumn(original);
        List<ChainEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(restored.get(0).isStopOnFailure()).isTrue();
        assertThat(restored.get(0).getInputContext()).containsEntry("env", "dev");
        assertThat(restored.get(1).getWorkflowId()).isEqualTo("wf-002");
        assertThat(restored.get(1).isStopOnFailure()).isFalse();
        assertThat(restored.get(1).getInputContext()).containsEntry("batch", 10);
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyList_when_emptyListInput() {
        String json = converter.convertToDatabaseColumn(new ArrayList<>());
        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void should_returnMutableList_when_deserializing() {
        String json = "[{\"workflowId\":\"wf-001\",\"label\":\"Test\",\"sortOrder\":0,\"stopOnFailure\":true,\"inputContext\":{}}]";

        List<ChainEntry> result = converter.convertToEntityAttribute(json);

        result.add(ChainEntry.builder().workflowId("wf-new").build());
        assertThat(result).hasSize(2);
    }

    @Test
    void should_returnMutableEmptyList_when_dbDataIsNull() {
        List<ChainEntry> result = converter.convertToEntityAttribute(null);

        result.add(ChainEntry.builder().workflowId("wf-001").build());
        assertThat(result).hasSize(1);
    }
}
