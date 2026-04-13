package com.automation.mapper;

import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookMapperTest {

    private WebhookMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WebhookMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            Workflow wf = Workflow.builder().id("wf-1").name("Git Deploy").build();

            WebhookTrigger webhook = WebhookTrigger.builder()
                    .id("wh-1")
                    .workflow(wf)
                    .secretToken("secret-abc")
                    .enabled(true)
                    .description("GitHub push trigger")
                    .defaultContext(Map.of("branch", "main"))
                    .lastTriggeredAt(now)
                    .triggerCount(12)
                    .createdAt(now)
                    .build();

            WebhookTriggerResponse response = mapper.toResponse(webhook);

            assertThat(response.id()).isEqualTo("wh-1");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.workflowName()).isEqualTo("Git Deploy");
            assertThat(response.secretToken()).isEqualTo("secret-abc");
            assertThat(response.enabled()).isTrue();
            assertThat(response.description()).isEqualTo("GitHub push trigger");
            assertThat(response.defaultContext()).containsEntry("branch", "main");
            assertThat(response.lastTriggeredAt()).isEqualTo(now);
            assertThat(response.triggerCount()).isEqualTo(12);
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void webhookUrl_alwaysNull() {
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            WebhookTrigger webhook = WebhookTrigger.builder()
                    .id("wh-2")
                    .workflow(wf)
                    .secretToken("token")
                    .build();

            WebhookTriggerResponse response = mapper.toResponse(webhook);

            assertThat(response.webhookUrl()).isNull();
        }

        @Test
        void nullWorkflow_workflowIdAndNameAreNull() {
            WebhookTrigger webhook = WebhookTrigger.builder()
                    .id("wh-3")
                    .workflow(null)
                    .secretToken("token")
                    .build();

            WebhookTriggerResponse response = mapper.toResponse(webhook);

            assertThat(response.workflowId()).isNull();
            assertThat(response.workflowName()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        WebhookTrigger w1 = WebhookTrigger.builder().id("wh-1").workflow(wf).secretToken("t1").build();
        WebhookTrigger w2 = WebhookTrigger.builder().id("wh-2").workflow(wf).secretToken("t2").build();

        List<WebhookTriggerResponse> responses = mapper.toResponseList(List.of(w1, w2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("wh-1");
        assertThat(responses.get(1).id()).isEqualTo("wh-2");
    }
}
