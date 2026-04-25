package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionTriggerResponseTest {

    @Test
    void should_storeAllValues_when_constructedWithFullValues() {
        Instant lastTriggered = Instant.parse("2026-04-20T10:00:00Z");
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        Map<String, Object> ctx = Map.of("key1", "value1", "count", 42);

        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "trigger-1",
                "src-wf-1",
                "Source Workflow",
                "tgt-wf-1",
                "Target Workflow",
                true,
                true,
                false,
                false,
                "Trigger after source completes",
                ctx,
                lastTriggered,
                5L,
                created
        );

        assertThat(response.id()).isEqualTo("trigger-1");
        assertThat(response.sourceWorkflowId()).isEqualTo("src-wf-1");
        assertThat(response.sourceWorkflowName()).isEqualTo("Source Workflow");
        assertThat(response.targetWorkflowId()).isEqualTo("tgt-wf-1");
        assertThat(response.targetWorkflowName()).isEqualTo("Target Workflow");
        assertThat(response.enabled()).isTrue();
        assertThat(response.triggerOnSuccess()).isTrue();
        assertThat(response.triggerOnFailure()).isFalse();
        assertThat(response.triggerAlways()).isFalse();
        assertThat(response.description()).isEqualTo("Trigger after source completes");
        assertThat(response.contextVariables()).containsEntry("key1", "value1").containsEntry("count", 42);
        assertThat(response.lastTriggeredAt()).isEqualTo(lastTriggered);
        assertThat(response.triggerCount()).isEqualTo(5L);
        assertThat(response.createdAt()).isEqualTo(created);
    }

    @Test
    void should_allowNullOptionalFields_when_constructed() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");

        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "trigger-2", "src", "Src", "tgt", "Tgt",
                false, false, false, false,
                null, null, null, 0L, created
        );

        assertThat(response.description()).isNull();
        assertThat(response.contextVariables()).isNull();
        assertThat(response.lastTriggeredAt()).isNull();
        assertThat(response.enabled()).isFalse();
        assertThat(response.triggerCount()).isZero();
        assertThat(response.createdAt()).isEqualTo(created);
    }

    @Test
    void should_allowEmptyContextVariables() {
        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, false, false, true,
                "desc", new HashMap<>(), null, 0L, Instant.now()
        );

        assertThat(response.contextVariables()).isEmpty();
    }

    @Test
    void should_supportTriggerAlwaysVariant() {
        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, false, false, true,
                "always", Map.of(), Instant.now(), 99L, Instant.now()
        );

        assertThat(response.triggerAlways()).isTrue();
        assertThat(response.triggerOnSuccess()).isFalse();
        assertThat(response.triggerOnFailure()).isFalse();
    }

    @Test
    void should_supportTriggerOnFailureVariant() {
        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, false, true, false,
                "on failure", Map.of(), null, 0L, Instant.now()
        );

        assertThat(response.triggerOnFailure()).isTrue();
        assertThat(response.triggerOnSuccess()).isFalse();
        assertThat(response.triggerAlways()).isFalse();
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        Instant last = Instant.parse("2026-04-20T10:00:00Z");
        Map<String, Object> ctx = Map.of("a", "b");

        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", ctx, last, 3L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", ctx, last, 3L, created);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id-1", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 1L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id-2", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 1L, created);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_enabledDiffers() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 1L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                false, true, false, false, "d", Map.of(), null, 1L, created);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_triggerOnSuccessDiffers() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 1L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, false, false, false, "d", Map.of(), null, 1L, created);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_triggerCountDiffers() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 1L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt", "Tgt",
                true, true, false, false, "d", Map.of(), null, 99L, created);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_targetWorkflowIdDiffers() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse a = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt-1", "Tgt", true, true, false, false, "d", Map.of(), null, 1L, created);
        CompletionTriggerResponse b = new CompletionTriggerResponse(
                "id", "src", "Src", "tgt-2", "Tgt", true, true, false, false, "d", Map.of(), null, 1L, created);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_includeKeyFields_inToString() {
        Instant created = Instant.parse("2026-04-01T08:00:00Z");
        CompletionTriggerResponse response = new CompletionTriggerResponse(
                "trigger-xyz", "src-1", "MyWorkflow", "tgt-1", "Target",
                true, true, false, false, "desc", Map.of(), null, 7L, created);

        String s = response.toString();
        assertThat(s).contains("trigger-xyz");
        assertThat(s).contains("src-1");
        assertThat(s).contains("MyWorkflow");
        assertThat(s).contains("tgt-1");
    }
}
