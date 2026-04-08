package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainRunTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToPending_when_usingBuilder() {
        ChainRun run = ChainRun.builder().build();

        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void should_defaultEntryRunsToEmptyList_when_usingBuilder() {
        ChainRun run = ChainRun.builder().build();

        assertThat(run.getEntryRuns()).isNotNull().isEmpty();
        assertThat(run.getEntryRuns()).isInstanceOf(ArrayList.class);
    }

    @Test
    void should_defaultInputContextToEmptyMap_when_usingBuilder() {
        ChainRun run = ChainRun.builder().build();

        assertThat(run.getInputContext()).isNotNull().isEmpty();
        assertThat(run.getInputContext()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_defaultMetadataToEmptyMap_when_usingBuilder() {
        ChainRun run = ChainRun.builder().build();

        assertThat(run.getMetadata()).isNotNull().isEmpty();
        assertThat(run.getMetadata()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        ChainRun run = ChainRun.builder().build();

        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getDurationMs()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeChain_when_setViaSetter() {
        ChainRun run = new ChainRun();
        WorkflowChain chain = new WorkflowChain();
        chain.setId("chain-1");

        run.setChain(chain);

        assertThat(run.getChain()).isSameAs(chain);
    }

    @Test
    void should_storeStartedAt_when_setViaSetter() {
        ChainRun run = new ChainRun();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        run.setStartedAt(ts);

        assertThat(run.getStartedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeCompletedAt_when_setViaSetter() {
        ChainRun run = new ChainRun();
        Instant ts = Instant.parse("2024-06-15T11:00:00Z");

        run.setCompletedAt(ts);

        assertThat(run.getCompletedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeDurationMs_when_setViaSetter() {
        ChainRun run = new ChainRun();

        run.setDurationMs(5000L);

        assertThat(run.getDurationMs()).isEqualTo(5000L);
    }

    @Test
    void should_storeError_when_setViaSetter() {
        ChainRun run = new ChainRun();

        run.setError("Something went wrong");

        assertThat(run.getError()).isEqualTo("Something went wrong");
    }

    @Test
    void should_storeTriggeredByScheduleId_when_setViaSetter() {
        ChainRun run = new ChainRun();

        run.setTriggeredByScheduleId("schedule-abc");

        assertThat(run.getTriggeredByScheduleId()).isEqualTo("schedule-abc");
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        ChainRun run = new ChainRun();

        assertThat(run.getId()).isNull();
    }

    @Test
    void should_defaultChainToNull_when_usingNoArgsConstructor() {
        ChainRun run = new ChainRun();

        assertThat(run.getChain()).isNull();
    }

    @Test
    void should_defaultErrorToNull_when_usingNoArgsConstructor() {
        ChainRun run = new ChainRun();

        assertThat(run.getError()).isNull();
    }

    @Test
    void should_defaultTriggeredByScheduleIdToNull_when_usingNoArgsConstructor() {
        ChainRun run = new ChainRun();

        assertThat(run.getTriggeredByScheduleId()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        WorkflowChain chain = new WorkflowChain();
        chain.setId("chain-1");
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant completed = Instant.parse("2024-06-15T10:05:00Z");
        List<ChainRunEntry> entries = new ArrayList<>(List.of(
                ChainRunEntry.builder().entryIndex(0).workflowId("wf-1").build()
        ));
        Map<String, Object> inputContext = new HashMap<>(Map.of("key", "value"));
        Map<String, Object> metadata = new HashMap<>(Map.of("source", "test"));

        ChainRun run = new ChainRun(
                "run-id", chain, RunStatus.RUNNING,
                entries, inputContext,
                started, completed, 300000L,
                "some error", metadata, "sched-1"
        );

        assertThat(run.getId()).isEqualTo("run-id");
        assertThat(run.getChain()).isSameAs(chain);
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getEntryRuns()).hasSize(1);
        assertThat(run.getInputContext()).containsEntry("key", "value");
        assertThat(run.getStartedAt()).isEqualTo(started);
        assertThat(run.getCompletedAt()).isEqualTo(completed);
        assertThat(run.getDurationMs()).isEqualTo(300000L);
        assertThat(run.getError()).isEqualTo("some error");
        assertThat(run.getMetadata()).containsEntry("source", "test");
        assertThat(run.getTriggeredByScheduleId()).isEqualTo("sched-1");
    }

    // --- List mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultEntryRuns() {
        ChainRun run = ChainRun.builder().build();
        ChainRunEntry entry = ChainRunEntry.builder().entryIndex(0).workflowId("wf-1").build();

        run.getEntryRuns().add(entry);

        assertThat(run.getEntryRuns()).hasSize(1);
        assertThat(run.getEntryRuns().get(0).getWorkflowId()).isEqualTo("wf-1");
    }

    @Test
    void should_allowRemovingEntries_when_entryRunsHasEntries() {
        List<ChainRunEntry> entries = new ArrayList<>(List.of(
                ChainRunEntry.builder().entryIndex(0).workflowId("wf-1").build(),
                ChainRunEntry.builder().entryIndex(1).workflowId("wf-2").build()
        ));
        ChainRun run = ChainRun.builder().entryRuns(entries).build();

        run.getEntryRuns().remove(0);

        assertThat(run.getEntryRuns()).hasSize(1);
        assertThat(run.getEntryRuns().get(0).getWorkflowId()).isEqualTo("wf-2");
    }

    // --- Map mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultInputContext() {
        ChainRun run = ChainRun.builder().build();

        run.getInputContext().put("env", "prod");

        assertThat(run.getInputContext()).hasSize(1).containsEntry("env", "prod");
    }

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultMetadata() {
        ChainRun run = ChainRun.builder().build();

        run.getMetadata().put("detail", "extra info");

        assertThat(run.getMetadata()).hasSize(1).containsEntry("detail", "extra info");
    }

    @Test
    void should_allowRemovingEntries_when_inputContextHasEntries() {
        Map<String, Object> ctx = new HashMap<>(Map.of("a", "1", "b", "2"));
        ChainRun run = ChainRun.builder().inputContext(ctx).build();

        run.getInputContext().remove("a");

        assertThat(run.getInputContext()).hasSize(1).containsEntry("b", "2");
    }

    @Test
    void should_allowRemovingEntries_when_metadataHasEntries() {
        Map<String, Object> meta = new HashMap<>(Map.of("x", "y", "z", "w"));
        ChainRun run = ChainRun.builder().metadata(meta).build();

        run.getMetadata().remove("x");

        assertThat(run.getMetadata()).hasSize(1).containsEntry("z", "w");
    }
}
