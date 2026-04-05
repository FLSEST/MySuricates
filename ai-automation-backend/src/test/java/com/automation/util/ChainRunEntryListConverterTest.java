package com.automation.util;

import com.automation.entity.ChainRunEntry;
import com.automation.entity.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainRunEntryListConverterTest {

    private ChainRunEntryListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ChainRunEntryListConverter();
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
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .workflowName("Test Workflow")
                .projectName("Test Project")
                .status(RunStatus.RUNNING)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result)
                .contains("\"workflowId\":\"wf-001\"")
                .contains("\"workflowName\":\"Test Workflow\"")
                .contains("\"projectName\":\"Test Project\"")
                .contains("\"entryIndex\":0")
                .contains("\"status\":\"RUNNING\"");
    }

    @Test
    void should_serializeMultipleEntries_when_attributeHasMultipleEntries() {
        ChainRunEntry entry1 = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .workflowName("First")
                .status(RunStatus.COMPLETED)
                .build();
        ChainRunEntry entry2 = ChainRunEntry.builder()
                .entryIndex(1)
                .workflowId("wf-002")
                .workflowName("Second")
                .status(RunStatus.PENDING)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry1, entry2));

        assertThat(result)
                .startsWith("[")
                .endsWith("]")
                .contains("\"wf-001\"")
                .contains("\"wf-002\"")
                .contains("\"First\"")
                .contains("\"Second\"");
    }

    @Test
    void should_serializeTimestamps_when_entryHasTimestamps() {
        Instant started = Instant.parse("2026-01-15T10:00:00Z");
        Instant completed = Instant.parse("2026-01-15T10:05:00Z");
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .status(RunStatus.COMPLETED)
                .startedAt(started)
                .completedAt(completed)
                .durationMs(300000L)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result)
                .contains("\"startedAt\"")
                .contains("\"completedAt\"")
                .contains("\"durationMs\":300000");
    }

    @Test
    void should_serializeWorkflowRunId_when_entryHasRunId() {
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .workflowRunId("run-abc-123")
                .status(RunStatus.RUNNING)
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result).contains("\"workflowRunId\":\"run-abc-123\"");
    }

    @Test
    void should_serializeDefaultStatus_when_statusNotExplicitlySet() {
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .build();

        String result = converter.convertToDatabaseColumn(List.of(entry));

        assertThat(result).contains("\"status\":\"PENDING\"");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyList_when_dbDataIsNull() {
        List<ChainRunEntry> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsEmpty() {
        List<ChainRunEntry> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsBlank() {
        List<ChainRunEntry> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeSingleEntry_when_dbDataHasOneEntry() {
        String json = "[{\"entryIndex\":0,\"workflowId\":\"wf-001\",\"workflowName\":\"Step 1\",\"projectName\":\"Proj\",\"workflowRunId\":null,\"status\":\"COMPLETED\",\"startedAt\":null,\"completedAt\":null,\"durationMs\":null}]";

        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(result.get(0).getWorkflowName()).isEqualTo("Step 1");
        assertThat(result.get(0).getProjectName()).isEqualTo("Proj");
        assertThat(result.get(0).getEntryIndex()).isEqualTo(0);
        assertThat(result.get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void should_deserializeMultipleEntries_when_dbDataHasMultipleEntries() {
        String json = "[{\"entryIndex\":0,\"workflowId\":\"wf-001\",\"workflowName\":\"A\",\"status\":\"COMPLETED\",\"durationMs\":5000}," +
                      "{\"entryIndex\":1,\"workflowId\":\"wf-002\",\"workflowName\":\"B\",\"status\":\"FAILED\",\"durationMs\":1000}]";

        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(result.get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(result.get(0).getDurationMs()).isEqualTo(5000L);
        assertThat(result.get(1).getWorkflowId()).isEqualTo("wf-002");
        assertThat(result.get(1).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(result.get(1).getDurationMs()).isEqualTo(1000L);
    }

    @Test
    void should_deserializeTimestamps_when_dbDataContainsInstants() {
        String json = "[{\"entryIndex\":0,\"workflowId\":\"wf-001\",\"status\":\"COMPLETED\",\"startedAt\":1737021600.000000000,\"completedAt\":1737021900.000000000,\"durationMs\":300000}]";

        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStartedAt()).isNotNull();
        assertThat(result.get(0).getCompletedAt()).isNotNull();
        assertThat(result.get(0).getDurationMs()).isEqualTo(300000L);
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "[broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to chain run entries");
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
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-roundtrip")
                .workflowName("Roundtrip WF")
                .projectName("Roundtrip Proj")
                .workflowRunId("run-123")
                .status(RunStatus.COMPLETED)
                .durationMs(42000L)
                .build();

        String json = converter.convertToDatabaseColumn(List.of(entry));
        List<ChainRunEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getEntryIndex()).isEqualTo(0);
        assertThat(restored.get(0).getWorkflowId()).isEqualTo("wf-roundtrip");
        assertThat(restored.get(0).getWorkflowName()).isEqualTo("Roundtrip WF");
        assertThat(restored.get(0).getProjectName()).isEqualTo("Roundtrip Proj");
        assertThat(restored.get(0).getWorkflowRunId()).isEqualTo("run-123");
        assertThat(restored.get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(restored.get(0).getDurationMs()).isEqualTo(42000L);
    }

    @Test
    void should_roundtrip_when_multipleEntriesWithAllFields() {
        Instant now = Instant.now();
        ChainRunEntry entry1 = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-001")
                .workflowName("First")
                .projectName("Proj A")
                .workflowRunId("run-001")
                .status(RunStatus.COMPLETED)
                .startedAt(now.minusSeconds(600))
                .completedAt(now.minusSeconds(300))
                .durationMs(300000L)
                .build();
        ChainRunEntry entry2 = ChainRunEntry.builder()
                .entryIndex(1)
                .workflowId("wf-002")
                .workflowName("Second")
                .projectName("Proj B")
                .workflowRunId("run-002")
                .status(RunStatus.FAILED)
                .startedAt(now.minusSeconds(300))
                .completedAt(now)
                .durationMs(300000L)
                .build();
        List<ChainRunEntry> original = new ArrayList<>();
        original.add(entry1);
        original.add(entry2);

        String json = converter.convertToDatabaseColumn(original);
        List<ChainRunEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).getWorkflowId()).isEqualTo("wf-001");
        assertThat(restored.get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(restored.get(0).getStartedAt()).isEqualTo(entry1.getStartedAt());
        assertThat(restored.get(0).getCompletedAt()).isEqualTo(entry1.getCompletedAt());
        assertThat(restored.get(1).getWorkflowId()).isEqualTo("wf-002");
        assertThat(restored.get(1).getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(restored.get(1).getDurationMs()).isEqualTo(300000L);
    }

    @Test
    void should_roundtrip_when_entryHasAllRunStatuses() {
        List<ChainRunEntry> entries = new ArrayList<>();
        int idx = 0;
        for (RunStatus status : RunStatus.values()) {
            entries.add(ChainRunEntry.builder()
                    .entryIndex(idx++)
                    .workflowId("wf-" + status.name().toLowerCase())
                    .status(status)
                    .build());
        }

        String json = converter.convertToDatabaseColumn(entries);
        List<ChainRunEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSameSizeAs(entries);
        for (int i = 0; i < entries.size(); i++) {
            assertThat(restored.get(i).getStatus()).isEqualTo(entries.get(i).getStatus());
        }
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyList_when_emptyListInput() {
        String json = converter.convertToDatabaseColumn(new ArrayList<>());
        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void should_returnMutableList_when_deserializing() {
        String json = "[{\"entryIndex\":0,\"workflowId\":\"wf-001\",\"status\":\"PENDING\"}]";

        List<ChainRunEntry> result = converter.convertToEntityAttribute(json);

        result.add(ChainRunEntry.builder().workflowId("wf-new").build());
        assertThat(result).hasSize(2);
    }

    @Test
    void should_returnMutableEmptyList_when_dbDataIsNull() {
        List<ChainRunEntry> result = converter.convertToEntityAttribute(null);

        result.add(ChainRunEntry.builder().workflowId("wf-001").build());
        assertThat(result).hasSize(1);
    }

    @Test
    void should_handleNullFields_when_entryHasMinimalData() {
        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(0)
                .workflowId("wf-minimal")
                .build();

        String json = converter.convertToDatabaseColumn(List.of(entry));
        List<ChainRunEntry> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getWorkflowId()).isEqualTo("wf-minimal");
        assertThat(restored.get(0).getWorkflowName()).isNull();
        assertThat(restored.get(0).getProjectName()).isNull();
        assertThat(restored.get(0).getWorkflowRunId()).isNull();
        assertThat(restored.get(0).getStartedAt()).isNull();
        assertThat(restored.get(0).getCompletedAt()).isNull();
        assertThat(restored.get(0).getDurationMs()).isNull();
        assertThat(restored.get(0).getStatus()).isEqualTo(RunStatus.PENDING);
    }
}
