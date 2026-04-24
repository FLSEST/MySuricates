package com.automation.entity;

import com.automation.entity.NotificationConfig.ChannelType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationConfigTest {

    @Test
    void should_applyBuilderDefaults_when_builtWithoutOverrides() {
        NotificationConfig config = NotificationConfig.builder().build();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getChannelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(config.isTriggerOnSuccess()).isTrue();
        assertThat(config.isTriggerOnFailure()).isTrue();
        assertThat(config.isTriggerAlways()).isFalse();
    }

    @Test
    void should_haveNullValueFields_when_builtWithoutOverrides() {
        NotificationConfig config = NotificationConfig.builder().build();

        assertThat(config.getId()).isNull();
        assertThat(config.getName()).isNull();
        assertThat(config.getWebhookUrl()).isNull();
        assertThat(config.getWorkflowId()).isNull();
        assertThat(config.getMessageTemplate()).isNull();
        assertThat(config.getCreatedAt()).isNull();
        assertThat(config.getUpdatedAt()).isNull();
    }

    @Test
    void should_applyExplicitBuilderValues_when_built() {
        Instant now = Instant.parse("2026-04-25T10:00:00Z");
        NotificationConfig config = NotificationConfig.builder()
                .id("config-1")
                .name("Slack alerts")
                .enabled(false)
                .channelType(ChannelType.DESKTOP)
                .webhookUrl("https://hooks.example.com/abc")
                .workflowId("wf-42")
                .triggerOnSuccess(false)
                .triggerOnFailure(false)
                .triggerAlways(true)
                .messageTemplate("Workflow {{workflow_name}} -> {{status}}")
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThat(config.getId()).isEqualTo("config-1");
        assertThat(config.getName()).isEqualTo("Slack alerts");
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getChannelType()).isEqualTo(ChannelType.DESKTOP);
        assertThat(config.getWebhookUrl()).isEqualTo("https://hooks.example.com/abc");
        assertThat(config.getWorkflowId()).isEqualTo("wf-42");
        assertThat(config.isTriggerOnSuccess()).isFalse();
        assertThat(config.isTriggerOnFailure()).isFalse();
        assertThat(config.isTriggerAlways()).isTrue();
        assertThat(config.getMessageTemplate()).isEqualTo("Workflow {{workflow_name}} -> {{status}}");
        assertThat(config.getCreatedAt()).isEqualTo(now);
        assertThat(config.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_setAndReturnAllFields_when_usingSetters() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-04-25T12:34:56Z");
        NotificationConfig config = new NotificationConfig();

        config.setId("id-2");
        config.setName("Desktop notifier");
        config.setEnabled(false);
        config.setChannelType(ChannelType.DESKTOP);
        config.setWebhookUrl("https://example.org/hook");
        config.setWorkflowId(null);
        config.setTriggerOnSuccess(false);
        config.setTriggerOnFailure(true);
        config.setTriggerAlways(true);
        config.setMessageTemplate("done");
        config.setCreatedAt(created);
        config.setUpdatedAt(updated);

        assertThat(config.getId()).isEqualTo("id-2");
        assertThat(config.getName()).isEqualTo("Desktop notifier");
        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getChannelType()).isEqualTo(ChannelType.DESKTOP);
        assertThat(config.getWebhookUrl()).isEqualTo("https://example.org/hook");
        assertThat(config.getWorkflowId()).isNull();
        assertThat(config.isTriggerOnSuccess()).isFalse();
        assertThat(config.isTriggerOnFailure()).isTrue();
        assertThat(config.isTriggerAlways()).isTrue();
        assertThat(config.getMessageTemplate()).isEqualTo("done");
        assertThat(config.getCreatedAt()).isEqualTo(created);
        assertThat(config.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void should_applyFieldInitializers_when_builtWithNoArgsConstructor() {
        NotificationConfig config = new NotificationConfig();

        // Lombok retains @Builder.Default field initializers in the no-args constructor
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getChannelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(config.isTriggerOnSuccess()).isTrue();
        assertThat(config.isTriggerOnFailure()).isTrue();
        assertThat(config.isTriggerAlways()).isFalse();
        assertThat(config.getId()).isNull();
        assertThat(config.getName()).isNull();
    }

    @Test
    void should_populateAllFields_when_usingAllArgsConstructor() {
        Instant created = Instant.parse("2026-03-01T00:00:00Z");
        Instant updated = Instant.parse("2026-03-02T00:00:00Z");

        NotificationConfig config = new NotificationConfig(
                "id-3",
                "All-args",
                true,
                ChannelType.WEBHOOK,
                "https://hook",
                "wf-9",
                true,
                false,
                false,
                "tpl",
                created,
                updated
        );

        assertThat(config.getId()).isEqualTo("id-3");
        assertThat(config.getName()).isEqualTo("All-args");
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getChannelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(config.getWebhookUrl()).isEqualTo("https://hook");
        assertThat(config.getWorkflowId()).isEqualTo("wf-9");
        assertThat(config.isTriggerOnSuccess()).isTrue();
        assertThat(config.isTriggerOnFailure()).isFalse();
        assertThat(config.isTriggerAlways()).isFalse();
        assertThat(config.getMessageTemplate()).isEqualTo("tpl");
        assertThat(config.getCreatedAt()).isEqualTo(created);
        assertThat(config.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void should_distinguishGlobalAndScopedConfig_when_workflowIdSet() {
        NotificationConfig globalConfig = NotificationConfig.builder().name("global").build();
        NotificationConfig scopedConfig = NotificationConfig.builder()
                .name("scoped")
                .workflowId("wf-123")
                .build();

        assertThat(globalConfig.getWorkflowId()).isNull();
        assertThat(scopedConfig.getWorkflowId()).isEqualTo("wf-123");
    }

    @Test
    void should_returnAllChannelTypes_when_callingValues() {
        assertThat(ChannelType.values())
                .containsExactly(ChannelType.WEBHOOK, ChannelType.DESKTOP);
    }

    @Test
    void should_resolveByName_when_callingValueOf() {
        assertThat(ChannelType.valueOf("WEBHOOK")).isEqualTo(ChannelType.WEBHOOK);
        assertThat(ChannelType.valueOf("DESKTOP")).isEqualTo(ChannelType.DESKTOP);
    }

    @Test
    void should_throwIllegalArgumentException_when_valueOfReceivesUnknownName() {
        assertThatThrownBy(() -> ChannelType.valueOf("EMAIL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
