package com.automation.mapper;

import com.automation.dto.response.NotificationConfigResponse;
import com.automation.entity.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationConfigMapperTest {

    private NotificationConfigMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new NotificationConfigMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            NotificationConfig config = NotificationConfig.builder()
                    .id("nc-1")
                    .name("Slack Alerts")
                    .enabled(true)
                    .channelType(NotificationConfig.ChannelType.WEBHOOK)
                    .webhookUrl("https://hooks.slack.com/services/abc")
                    .workflowId("wf-1")
                    .triggerOnSuccess(true)
                    .triggerOnFailure(true)
                    .triggerAlways(false)
                    .messageTemplate("{{workflow_name}} finished with {{status}}")
                    .createdAt(now)
                    .build();

            NotificationConfigResponse response = mapper.toResponse(config);

            assertThat(response.id()).isEqualTo("nc-1");
            assertThat(response.name()).isEqualTo("Slack Alerts");
            assertThat(response.enabled()).isTrue();
            assertThat(response.channelType()).isEqualTo(NotificationConfig.ChannelType.WEBHOOK);
            assertThat(response.webhookUrl()).isEqualTo("https://hooks.slack.com/services/abc");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.triggerOnSuccess()).isTrue();
            assertThat(response.triggerOnFailure()).isTrue();
            assertThat(response.triggerAlways()).isFalse();
            assertThat(response.messageTemplate()).isEqualTo("{{workflow_name}} finished with {{status}}");
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void desktopChannelType_mappedCorrectly() {
            NotificationConfig config = NotificationConfig.builder()
                    .name("Desktop")
                    .channelType(NotificationConfig.ChannelType.DESKTOP)
                    .build();

            NotificationConfigResponse response = mapper.toResponse(config);

            assertThat(response.channelType()).isEqualTo(NotificationConfig.ChannelType.DESKTOP);
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            NotificationConfig config = NotificationConfig.builder()
                    .name("Minimal")
                    .channelType(NotificationConfig.ChannelType.WEBHOOK)
                    .build();

            NotificationConfigResponse response = mapper.toResponse(config);

            assertThat(response.webhookUrl()).isNull();
            assertThat(response.workflowId()).isNull();
            assertThat(response.messageTemplate()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        NotificationConfig c1 = NotificationConfig.builder().id("nc-1").name("A").channelType(NotificationConfig.ChannelType.WEBHOOK).build();
        NotificationConfig c2 = NotificationConfig.builder().id("nc-2").name("B").channelType(NotificationConfig.ChannelType.DESKTOP).build();

        List<NotificationConfigResponse> responses = mapper.toResponseList(List.of(c1, c2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("nc-1");
        assertThat(responses.get(1).id()).isEqualTo("nc-2");
    }
}
