package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTriggerTest {

    // --- Builder defaults ---

    @Test
    void should_defaultEnabledToTrue_when_usingBuilder() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        assertThat(trigger.isEnabled()).isTrue();
    }

    @Test
    void should_defaultDefaultContextToEmptyMap_when_usingBuilder() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        assertThat(trigger.getDefaultContext()).isNotNull().isEmpty();
        assertThat(trigger.getDefaultContext()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_defaultTriggerCountToZero_when_usingBuilder() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        assertThat(trigger.getTriggerCount()).isEqualTo(0L);
    }

    @Test
    void should_haveNullId_when_builtWithDefaults() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        assertThat(trigger.getId()).isNull();
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        assertThat(trigger.getLastTriggeredAt()).isNull();
        assertThat(trigger.getCreatedAt()).isNull();
        assertThat(trigger.getUpdatedAt()).isNull();
    }

    // --- Workflow assignment ---

    @Test
    void should_storeWorkflow_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();
        Workflow workflow = new Workflow();
        workflow.setId("wf-123");

        trigger.setWorkflow(workflow);

        assertThat(trigger.getWorkflow()).isSameAs(workflow);
        assertThat(trigger.getWorkflow().getId()).isEqualTo("wf-123");
    }

    @Test
    void should_storeWorkflow_when_setViaBuilder() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-456");

        WebhookTrigger trigger = WebhookTrigger.builder()
                .workflow(workflow)
                .build();

        assertThat(trigger.getWorkflow()).isSameAs(workflow);
    }

    @Test
    void should_defaultWorkflowToNull_when_usingNoArgsConstructor() {
        WebhookTrigger trigger = new WebhookTrigger();

        assertThat(trigger.getWorkflow()).isNull();
    }

    // --- Getter/Setter roundtrips ---

    @Test
    void should_storeSecretToken_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();

        trigger.setSecretToken("tok-secret-abc123");

        assertThat(trigger.getSecretToken()).isEqualTo("tok-secret-abc123");
    }

    @Test
    void should_storeDescription_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();

        trigger.setDescription("Triggers deploy pipeline");

        assertThat(trigger.getDescription()).isEqualTo("Triggers deploy pipeline");
    }

    @Test
    void should_storeLastTriggeredAt_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        trigger.setLastTriggeredAt(ts);

        assertThat(trigger.getLastTriggeredAt()).isEqualTo(ts);
    }

    @Test
    void should_storeEnabled_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();

        trigger.setEnabled(false);

        assertThat(trigger.isEnabled()).isFalse();
    }

    @Test
    void should_storeTriggerCount_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();

        trigger.setTriggerCount(42L);

        assertThat(trigger.getTriggerCount()).isEqualTo(42L);
    }

    // --- Audit fields ---

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        trigger.setCreatedAt(created);

        assertThat(trigger.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_storeUpdatedAt_when_setViaSetter() {
        WebhookTrigger trigger = new WebhookTrigger();
        Instant updated = Instant.parse("2024-06-15T12:00:00Z");

        trigger.setUpdatedAt(updated);

        assertThat(trigger.getUpdatedAt()).isEqualTo(updated);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultSecretTokenToNull_when_usingNoArgsConstructor() {
        WebhookTrigger trigger = new WebhookTrigger();

        assertThat(trigger.getSecretToken()).isNull();
    }

    @Test
    void should_defaultDescriptionToNull_when_usingNoArgsConstructor() {
        WebhookTrigger trigger = new WebhookTrigger();

        assertThat(trigger.getDescription()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-all");
        Instant lastTriggered = Instant.parse("2024-06-15T10:00:00Z");
        Instant created = Instant.parse("2024-06-14T08:00:00Z");
        Instant updated = Instant.parse("2024-06-15T12:00:00Z");
        Map<String, Object> context = new HashMap<>(Map.of("env", "prod"));

        WebhookTrigger trigger = new WebhookTrigger(
                "trigger-id", workflow, "secret-token-xyz",
                true, "Deploy webhook", "encrypted-signing-secret", context,
                lastTriggered, 5L, created, updated
        );

        assertThat(trigger.getId()).isEqualTo("trigger-id");
        assertThat(trigger.getWorkflow()).isSameAs(workflow);
        assertThat(trigger.getSecretToken()).isEqualTo("secret-token-xyz");
        assertThat(trigger.isEnabled()).isTrue();
        assertThat(trigger.getDescription()).isEqualTo("Deploy webhook");
        assertThat(trigger.getSigningSecretEnc()).isEqualTo("encrypted-signing-secret");
        assertThat(trigger.getDefaultContext()).containsEntry("env", "prod");
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(lastTriggered);
        assertThat(trigger.getTriggerCount()).isEqualTo(5L);
        assertThat(trigger.getCreatedAt()).isEqualTo(created);
        assertThat(trigger.getUpdatedAt()).isEqualTo(updated);
    }

    // --- Map mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultContext() {
        WebhookTrigger trigger = WebhookTrigger.builder().build();

        trigger.getDefaultContext().put("branch", "main");

        assertThat(trigger.getDefaultContext()).hasSize(1).containsEntry("branch", "main");
    }

    @Test
    void should_allowRemovingEntries_when_defaultContextHasEntries() {
        Map<String, Object> ctx = new HashMap<>(Map.of("a", "1", "b", "2"));
        WebhookTrigger trigger = WebhookTrigger.builder().defaultContext(ctx).build();

        trigger.getDefaultContext().remove("a");

        assertThat(trigger.getDefaultContext()).hasSize(1).containsEntry("b", "2");
    }
}
