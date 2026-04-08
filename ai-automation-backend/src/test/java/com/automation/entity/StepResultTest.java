package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepResultTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToPending_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void should_defaultMetadataToEmptyMap_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getMetadata()).isNotNull().isEmpty();
        assertThat(result.getMetadata()).isInstanceOf(HashMap.class);
    }

    // --- Null defaults ---

    @Test
    void should_defaultStartedAtToNull_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getStartedAt()).isNull();
    }

    @Test
    void should_defaultCompletedAtToNull_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getCompletedAt()).isNull();
    }

    @Test
    void should_defaultDurationMsToNull_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getDurationMs()).isNull();
    }

    @Test
    void should_defaultContextDeltaToNull_when_usingBuilder() {
        StepResult result = StepResult.builder().build();

        assertThat(result.getContextDelta()).isNull();
    }

    // --- Getter / Setter ---

    @Test
    void should_storeWorkflowRun_when_setViaSetter() {
        StepResult result = new StepResult();
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");

        result.setWorkflowRun(run);

        assertThat(result.getWorkflowRun()).isSameAs(run);
    }

    @Test
    void should_storeStepIndex_when_setViaSetter() {
        StepResult result = new StepResult();

        result.setStepIndex(3);

        assertThat(result.getStepIndex()).isEqualTo(3);
    }

    @Test
    void should_storeInput_when_setViaSetter() {
        StepResult result = new StepResult();

        result.setInput("some input text");

        assertThat(result.getInput()).isEqualTo("some input text");
    }

    @Test
    void should_storeOutput_when_setViaSetter() {
        StepResult result = new StepResult();

        result.setOutput("some output text");

        assertThat(result.getOutput()).isEqualTo("some output text");
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        StepResult result = new StepResult();

        result.setStatus(StepStatus.COMPLETED);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void should_storeStartedAt_when_setViaSetter() {
        StepResult result = new StepResult();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        result.setStartedAt(ts);

        assertThat(result.getStartedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeCompletedAt_when_setViaSetter() {
        StepResult result = new StepResult();
        Instant ts = Instant.parse("2024-06-15T11:00:00Z");

        result.setCompletedAt(ts);

        assertThat(result.getCompletedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeDurationMs_when_setViaSetter() {
        StepResult result = new StepResult();

        result.setDurationMs(5000L);

        assertThat(result.getDurationMs()).isEqualTo(5000L);
    }

    @Test
    void should_storeContextDelta_when_setViaSetter() {
        StepResult result = new StepResult();
        Map<String, Object> delta = new HashMap<>(Map.of("var1", "value1"));

        result.setContextDelta(delta);

        assertThat(result.getContextDelta()).containsEntry("var1", "value1");
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        StepResult result = new StepResult();

        assertThat(result.getId()).isNull();
    }

    @Test
    void should_defaultWorkflowRunToNull_when_usingNoArgsConstructor() {
        StepResult result = new StepResult();

        assertThat(result.getWorkflowRun()).isNull();
    }

    @Test
    void should_defaultInputToNull_when_usingNoArgsConstructor() {
        StepResult result = new StepResult();

        assertThat(result.getInput()).isNull();
    }

    @Test
    void should_defaultOutputToNull_when_usingNoArgsConstructor() {
        StepResult result = new StepResult();

        assertThat(result.getOutput()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant completed = Instant.parse("2024-06-15T10:05:00Z");
        Map<String, Object> metadata = new HashMap<>(Map.of("key", "value"));
        Map<String, Object> contextDelta = new HashMap<>(Map.of("var1", "val1"));

        StepResult result = new StepResult(
                "step-id", run, 2,
                "input text", "output text",
                StepStatus.COMPLETED,
                started, completed, 300000L,
                metadata, contextDelta
        );

        assertThat(result.getId()).isEqualTo("step-id");
        assertThat(result.getWorkflowRun()).isSameAs(run);
        assertThat(result.getStepIndex()).isEqualTo(2);
        assertThat(result.getInput()).isEqualTo("input text");
        assertThat(result.getOutput()).isEqualTo("output text");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getStartedAt()).isEqualTo(started);
        assertThat(result.getCompletedAt()).isEqualTo(completed);
        assertThat(result.getDurationMs()).isEqualTo(300000L);
        assertThat(result.getMetadata()).containsEntry("key", "value");
        assertThat(result.getContextDelta()).containsEntry("var1", "val1");
    }

    // --- Metadata mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultMetadata() {
        StepResult result = StepResult.builder().build();

        result.getMetadata().put("detail", "extra info");

        assertThat(result.getMetadata()).hasSize(1).containsEntry("detail", "extra info");
    }

    @Test
    void should_allowRemovingEntries_when_metadataHasEntries() {
        Map<String, Object> meta = new HashMap<>(Map.of("a", "1", "b", "2"));
        StepResult result = StepResult.builder().metadata(meta).build();

        result.getMetadata().remove("a");

        assertThat(result.getMetadata()).hasSize(1).containsEntry("b", "2");
    }
}
