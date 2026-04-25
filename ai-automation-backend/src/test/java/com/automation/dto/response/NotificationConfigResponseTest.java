package com.automation.dto.response;

import com.automation.entity.NotificationConfig.ChannelType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConfigResponseTest {

    private static final Instant NOW = Instant.parse("2024-06-01T10:00:00Z");

    // --- Constructor & accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        var response = new NotificationConfigResponse(
                "id-1", "My Notification", true, ChannelType.WEBHOOK,
                "https://hooks.example.com/xyz", "wf-99",
                true, false, false, "Workflow {{ name }} finished", NOW
        );

        assertThat(response.id()).isEqualTo("id-1");
        assertThat(response.name()).isEqualTo("My Notification");
        assertThat(response.enabled()).isTrue();
        assertThat(response.channelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(response.webhookUrl()).isEqualTo("https://hooks.example.com/xyz");
        assertThat(response.workflowId()).isEqualTo("wf-99");
        assertThat(response.triggerOnSuccess()).isTrue();
        assertThat(response.triggerOnFailure()).isFalse();
        assertThat(response.triggerAlways()).isFalse();
        assertThat(response.messageTemplate()).isEqualTo("Workflow {{ name }} finished");
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void should_returnDisabled_when_enabledIsFalse() {
        var response = new NotificationConfigResponse(
                "id-2", "Off", false, ChannelType.DESKTOP,
                null, null, false, false, false, null, null
        );

        assertThat(response.enabled()).isFalse();
    }

    @Test
    void should_returnDesktopChannelType_when_channelTypeIsDesktop() {
        var response = new NotificationConfigResponse(
                "id-3", "Desktop", true, ChannelType.DESKTOP,
                null, null, false, true, false, null, NOW
        );

        assertThat(response.channelType()).isEqualTo(ChannelType.DESKTOP);
        assertThat(response.triggerOnFailure()).isTrue();
        assertThat(response.triggerOnSuccess()).isFalse();
    }

    @Test
    void should_returnTriggerAlwaysTrue_when_triggerAlwaysSet() {
        var response = new NotificationConfigResponse(
                "id-4", "Always", true, ChannelType.WEBHOOK,
                "https://hooks.example.com", "wf-1",
                false, false, true, null, NOW
        );

        assertThat(response.triggerAlways()).isTrue();
        assertThat(response.triggerOnSuccess()).isFalse();
        assertThat(response.triggerOnFailure()).isFalse();
    }

    @Test
    void should_acceptNullOptionalFields_when_constructedWithNulls() {
        var response = new NotificationConfigResponse(
                null, null, false, null,
                null, null, false, false, false, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.channelType()).isNull();
        assertThat(response.webhookUrl()).isNull();
        assertThat(response.workflowId()).isNull();
        assertThat(response.messageTemplate()).isNull();
        assertThat(response.createdAt()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_bothHaveSameValues() {
        var a = new NotificationConfigResponse(
                "id-1", "N", true, ChannelType.WEBHOOK,
                "https://h.example.com", "wf-1",
                true, false, false, "tmpl", NOW
        );
        var b = new NotificationConfigResponse(
                "id-1", "N", true, ChannelType.WEBHOOK,
                "https://h.example.com", "wf-1",
                true, false, false, "tmpl", NOW
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveAllNulls() {
        var a = new NotificationConfigResponse(null, null, false, null, null, null, false, false, false, null, null);
        var b = new NotificationConfigResponse(null, null, false, null, null, null, false, false, false, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        var a = new NotificationConfigResponse("id-A", "N", true, ChannelType.WEBHOOK, null, null, false, false, false, null, NOW);
        var b = new NotificationConfigResponse("id-B", "N", true, ChannelType.WEBHOOK, null, null, false, false, false, null, NOW);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_channelTypeDiffers() {
        var a = new NotificationConfigResponse("id-1", "N", true, ChannelType.WEBHOOK, null, null, false, false, false, null, NOW);
        var b = new NotificationConfigResponse("id-1", "N", true, ChannelType.DESKTOP, null, null, false, false, false, null, NOW);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new NotificationConfigResponse("id-1", "N", false, ChannelType.WEBHOOK, null, null, false, false, false, null, null);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containAllFieldNames_when_toStringCalled() {
        var response = new NotificationConfigResponse(
                "id-1", "My Notif", true, ChannelType.WEBHOOK,
                "https://hooks.example.com", "wf-1",
                true, false, false, "template text", NOW
        );
        var str = response.toString();

        assertThat(str).contains("id");
        assertThat(str).contains("name");
        assertThat(str).contains("enabled");
        assertThat(str).contains("channelType");
        assertThat(str).contains("webhookUrl");
        assertThat(str).contains("workflowId");
        assertThat(str).contains("messageTemplate");
        assertThat(str).contains("createdAt");
    }

    @Test
    void should_containFieldValues_when_toStringCalledWithPopulatedRecord() {
        var response = new NotificationConfigResponse(
                "id-42", "Webhook Alert", true, ChannelType.WEBHOOK,
                "https://hooks.example.com/abc", "wf-7",
                true, true, false, "done: {{ name }}", NOW
        );
        var str = response.toString();

        assertThat(str).contains("id-42");
        assertThat(str).contains("Webhook Alert");
        assertThat(str).contains("WEBHOOK");
        assertThat(str).contains("wf-7");
    }
}
