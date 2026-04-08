package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowChainTest {

    // --- Builder defaults ---

    @Test
    void should_defaultEntriesToEmptyList_when_usingBuilder() {
        WorkflowChain chain = WorkflowChain.builder().build();

        assertThat(chain.getEntries()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultStopOnFailureToTrue_when_usingBuilder() {
        WorkflowChain chain = WorkflowChain.builder().build();

        assertThat(chain.isStopOnFailure()).isTrue();
    }

    @Test
    void should_defaultMetadataToEmptyMap_when_usingBuilder() {
        WorkflowChain chain = WorkflowChain.builder().build();

        assertThat(chain.getMetadata()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultRunsToEmptyList_when_usingBuilder() {
        WorkflowChain chain = WorkflowChain.builder().build();

        assertThat(chain.getRuns()).isNotNull().isEmpty();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();

        chain.setId("chain-123");

        assertThat(chain.getId()).isEqualTo("chain-123");
    }

    @Test
    void should_storeName_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();

        chain.setName("My Chain");

        assertThat(chain.getName()).isEqualTo("My Chain");
    }

    @Test
    void should_storeDescription_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();

        chain.setDescription("A test chain description");

        assertThat(chain.getDescription()).isEqualTo("A test chain description");
    }

    @Test
    void should_storeIcon_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();

        chain.setIcon("link");

        assertThat(chain.getIcon()).isEqualTo("link");
    }

    @Test
    void should_storeEntries_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();
        List<ChainEntry> entries = new ArrayList<>();
        entries.add(ChainEntry.builder().workflowId("wf-1").sortOrder(0).build());

        chain.setEntries(entries);

        assertThat(chain.getEntries()).hasSize(1);
        assertThat(chain.getEntries().get(0).getWorkflowId()).isEqualTo("wf-1");
    }

    @Test
    void should_storeStopOnFailure_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();

        chain.setStopOnFailure(false);

        assertThat(chain.isStopOnFailure()).isFalse();
    }

    @Test
    void should_storeMetadata_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();
        Map<String, Object> metadata = new HashMap<>(Map.of("key", "value"));

        chain.setMetadata(metadata);

        assertThat(chain.getMetadata()).containsEntry("key", "value");
    }

    @Test
    void should_storeRuns_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();
        List<ChainRun> runs = new ArrayList<>();
        runs.add(new ChainRun());

        chain.setRuns(runs);

        assertThat(chain.getRuns()).hasSize(1);
    }

    // --- Audit fields ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getCreatedAt()).isNull();
    }

    @Test
    void should_defaultUpdatedAtToNull_when_newInstance() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getUpdatedAt()).isNull();
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        chain.setCreatedAt(created);

        assertThat(chain.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_storeUpdatedAt_when_setViaSetter() {
        WorkflowChain chain = new WorkflowChain();
        Instant updated = Instant.parse("2024-06-01T12:00:00Z");

        chain.setUpdatedAt(updated);

        assertThat(chain.getUpdatedAt()).isEqualTo(updated);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getId()).isNull();
    }

    @Test
    void should_defaultNameToNull_when_usingNoArgsConstructor() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getName()).isNull();
    }

    @Test
    void should_defaultDescriptionToNull_when_usingNoArgsConstructor() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getDescription()).isNull();
    }

    @Test
    void should_defaultIconToNull_when_usingNoArgsConstructor() {
        WorkflowChain chain = new WorkflowChain();

        assertThat(chain.getIcon()).isNull();
    }

    // --- Builder with explicit values ---

    @Test
    void should_setAllFields_when_usingBuilderWithExplicitValues() {
        List<ChainEntry> entries = new ArrayList<>();
        entries.add(ChainEntry.builder().workflowId("wf-1").sortOrder(0).build());
        entries.add(ChainEntry.builder().workflowId("wf-2").sortOrder(1).build());
        Map<String, Object> metadata = new HashMap<>(Map.of("env", "prod"));
        List<ChainRun> runs = new ArrayList<>();
        Instant now = Instant.now();

        WorkflowChain chain = WorkflowChain.builder()
                .id("chain-1")
                .name("Deploy Pipeline")
                .description("Full deploy chain")
                .icon("rocket")
                .entries(entries)
                .stopOnFailure(false)
                .metadata(metadata)
                .runs(runs)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThat(chain.getId()).isEqualTo("chain-1");
        assertThat(chain.getName()).isEqualTo("Deploy Pipeline");
        assertThat(chain.getDescription()).isEqualTo("Full deploy chain");
        assertThat(chain.getIcon()).isEqualTo("rocket");
        assertThat(chain.getEntries()).hasSize(2);
        assertThat(chain.isStopOnFailure()).isFalse();
        assertThat(chain.getMetadata()).containsEntry("env", "prod");
        assertThat(chain.getRuns()).isSameAs(runs);
        assertThat(chain.getCreatedAt()).isEqualTo(now);
        assertThat(chain.getUpdatedAt()).isEqualTo(now);
    }

    // --- Entries mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultEntries() {
        WorkflowChain chain = WorkflowChain.builder().build();

        chain.getEntries().add(ChainEntry.builder().workflowId("wf-new").build());

        assertThat(chain.getEntries()).hasSize(1);
        assertThat(chain.getEntries().get(0).getWorkflowId()).isEqualTo("wf-new");
    }

    @Test
    void should_allowRemovingEntries_when_entriesHasItems() {
        List<ChainEntry> entries = new ArrayList<>();
        entries.add(ChainEntry.builder().workflowId("wf-a").build());
        entries.add(ChainEntry.builder().workflowId("wf-b").build());
        WorkflowChain chain = WorkflowChain.builder()
                .entries(entries)
                .build();

        chain.getEntries().remove(0);

        assertThat(chain.getEntries()).hasSize(1);
        assertThat(chain.getEntries().get(0).getWorkflowId()).isEqualTo("wf-b");
    }

    // --- Metadata mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultMetadata() {
        WorkflowChain chain = WorkflowChain.builder().build();

        chain.getMetadata().put("detail", "extra info");

        assertThat(chain.getMetadata()).hasSize(1).containsEntry("detail", "extra info");
    }

    @Test
    void should_allowRemovingEntries_when_metadataHasEntries() {
        Map<String, Object> metadata = new HashMap<>(Map.of("a", "1", "b", "2"));
        WorkflowChain chain = WorkflowChain.builder()
                .metadata(metadata)
                .build();

        chain.getMetadata().remove("a");

        assertThat(chain.getMetadata()).hasSize(1).containsEntry("b", "2");
    }

    // --- Runs mutability ---

    @Test
    void should_allowAddingRuns_when_usingBuilderDefaultRuns() {
        WorkflowChain chain = WorkflowChain.builder().build();

        chain.getRuns().add(new ChainRun());

        assertThat(chain.getRuns()).hasSize(1);
    }

    @Test
    void should_allowRemovingRuns_when_runsHasItems() {
        List<ChainRun> runs = new ArrayList<>();
        runs.add(new ChainRun());
        runs.add(new ChainRun());
        WorkflowChain chain = WorkflowChain.builder()
                .runs(runs)
                .build();

        chain.getRuns().remove(0);

        assertThat(chain.getRuns()).hasSize(1);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        List<ChainEntry> entries = new ArrayList<>();
        entries.add(ChainEntry.builder().workflowId("wf-1").build());
        Map<String, Object> metadata = new HashMap<>(Map.of("x", "y"));
        List<ChainRun> runs = new ArrayList<>();
        Instant now = Instant.now();

        WorkflowChain chain = new WorkflowChain(
                "chain-all", "All Args Chain", "All args desc",
                "star", entries, true, metadata, runs,
                now, now
        );

        assertThat(chain.getId()).isEqualTo("chain-all");
        assertThat(chain.getName()).isEqualTo("All Args Chain");
        assertThat(chain.getDescription()).isEqualTo("All args desc");
        assertThat(chain.getIcon()).isEqualTo("star");
        assertThat(chain.getEntries()).hasSize(1);
        assertThat(chain.isStopOnFailure()).isTrue();
        assertThat(chain.getMetadata()).containsEntry("x", "y");
        assertThat(chain.getRuns()).isSameAs(runs);
        assertThat(chain.getCreatedAt()).isEqualTo(now);
        assertThat(chain.getUpdatedAt()).isEqualTo(now);
    }
}
