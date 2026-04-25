package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookTriggerResponseTest {

    private static final Instant CREATED_AT = Instant.parse("2024-06-01T10:00:00Z");
    private static final Instant LAST_TRIGGERED_AT = Instant.parse("2024-06-02T11:30:00Z");

    private WebhookTriggerResponse sample() {
        return new WebhookTriggerResponse(
                "wh-1",
                "wf-42",
                "My Workflow",
                "secret-token-xyz",
                "https://example.com/api/v1/webhooks/wh-1",
                true,
                "Triggers daily report",
                Map.of("env", "prod", "level", 5),
                LAST_TRIGGERED_AT,
                7L,
                CREATED_AT
        );
    }

    // --- Constructor & accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        var response = sample();

        assertThat(response.id()).isEqualTo("wh-1");
        assertThat(response.workflowId()).isEqualTo("wf-42");
        assertThat(response.workflowName()).isEqualTo("My Workflow");
        assertThat(response.secretToken()).isEqualTo("secret-token-xyz");
        assertThat(response.webhookUrl()).isEqualTo("https://example.com/api/v1/webhooks/wh-1");
        assertThat(response.enabled()).isTrue();
        assertThat(response.description()).isEqualTo("Triggers daily report");
        assertThat(response.defaultContext()).containsEntry("env", "prod").containsEntry("level", 5);
        assertThat(response.lastTriggeredAt()).isEqualTo(LAST_TRIGGERED_AT);
        assertThat(response.triggerCount()).isEqualTo(7L);
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_returnDisabled_when_enabledIsFalse() {
        var response = new WebhookTriggerResponse(
                "wh-2", "wf-1", "Name", "tok", "url",
                false, null, Map.of(), null, 0L, CREATED_AT
        );

        assertThat(response.enabled()).isFalse();
    }

    @Test
    void should_acceptNullOptionalFields_when_constructedWithNulls() {
        var response = new WebhookTriggerResponse(
                null, null, null, null, null,
                false, null, null, null, 0L, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.workflowId()).isNull();
        assertThat(response.workflowName()).isNull();
        assertThat(response.secretToken()).isNull();
        assertThat(response.webhookUrl()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.defaultContext()).isNull();
        assertThat(response.lastTriggeredAt()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.triggerCount()).isZero();
    }

    @Test
    void should_returnEmptyMap_when_defaultContextIsEmpty() {
        var response = new WebhookTriggerResponse(
                "id", "wf", "n", "t", "u",
                true, "desc", Map.of(), CREATED_AT, 0L, CREATED_AT
        );

        assertThat(response.defaultContext()).isEmpty();
    }

    @Test
    void should_returnHighTriggerCount_when_largeValueProvided() {
        var response = new WebhookTriggerResponse(
                "id", "wf", "n", "t", "u",
                true, "desc", Map.of(), CREATED_AT, Long.MAX_VALUE, CREATED_AT
        );

        assertThat(response.triggerCount()).isEqualTo(Long.MAX_VALUE);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_bothHaveSameValues() {
        var a = sample();
        var b = sample();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveAllNulls() {
        var a = new WebhookTriggerResponse(null, null, null, null, null, false, null, null, null, 0L, null);
        var b = new WebhookTriggerResponse(null, null, null, null, null, false, null, null, null, 0L, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        var a = sample();
        var b = new WebhookTriggerResponse(
                "different-id", "wf-42", "My Workflow", "secret-token-xyz",
                "https://example.com/api/v1/webhooks/wh-1", true, "Triggers daily report",
                Map.of("env", "prod", "level", 5), LAST_TRIGGERED_AT, 7L, CREATED_AT
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_triggerCountDiffers() {
        var a = sample();
        var b = new WebhookTriggerResponse(
                "wh-1", "wf-42", "My Workflow", "secret-token-xyz",
                "https://example.com/api/v1/webhooks/wh-1", true, "Triggers daily report",
                Map.of("env", "prod", "level", 5), LAST_TRIGGERED_AT, 999L, CREATED_AT
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        assertThat(sample()).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        assertThat(sample()).isNotEqualTo("a string");
    }

    // --- toString ---

    @Test
    void should_containAllFieldNames_when_toStringCalled() {
        var s = sample().toString();

        assertThat(s).contains("id", "workflowId", "workflowName", "secretToken",
                "webhookUrl", "enabled", "description", "defaultContext",
                "lastTriggeredAt", "triggerCount", "createdAt");
    }

    @Test
    void should_containFieldValues_when_toStringCalledWithPopulatedRecord() {
        var s = sample().toString();

        assertThat(s).contains("wh-1", "wf-42", "My Workflow", "secret-token-xyz", "7");
    }
}
