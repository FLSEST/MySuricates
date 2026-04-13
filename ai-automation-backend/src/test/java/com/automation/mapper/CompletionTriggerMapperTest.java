package com.automation.mapper;

import com.automation.dto.response.CompletionTriggerResponse;
import com.automation.entity.CompletionTrigger;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionTriggerMapperTest {

    private CompletionTriggerMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new CompletionTriggerMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            Workflow source = Workflow.builder().id("wf-src").name("Source WF").build();
            Workflow target = Workflow.builder().id("wf-tgt").name("Target WF").build();

            CompletionTrigger trigger = CompletionTrigger.builder()
                    .id("ct-1")
                    .sourceWorkflow(source)
                    .targetWorkflow(target)
                    .enabled(true)
                    .triggerOnSuccess(true)
                    .triggerOnFailure(false)
                    .triggerAlways(false)
                    .description("Run on success")
                    .contextVariables(Map.of("env", "prod"))
                    .lastTriggeredAt(now)
                    .triggerCount(7)
                    .createdAt(now)
                    .build();

            CompletionTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.id()).isEqualTo("ct-1");
            assertThat(response.sourceWorkflowId()).isEqualTo("wf-src");
            assertThat(response.sourceWorkflowName()).isEqualTo("Source WF");
            assertThat(response.targetWorkflowId()).isEqualTo("wf-tgt");
            assertThat(response.targetWorkflowName()).isEqualTo("Target WF");
            assertThat(response.enabled()).isTrue();
            assertThat(response.triggerOnSuccess()).isTrue();
            assertThat(response.triggerOnFailure()).isFalse();
            assertThat(response.triggerAlways()).isFalse();
            assertThat(response.description()).isEqualTo("Run on success");
            assertThat(response.contextVariables()).containsEntry("env", "prod");
            assertThat(response.lastTriggeredAt()).isEqualTo(now);
            assertThat(response.triggerCount()).isEqualTo(7);
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void nullWorkflows_idsAndNamesAreNull() {
            CompletionTrigger trigger = CompletionTrigger.builder()
                    .id("ct-2")
                    .sourceWorkflow(null)
                    .targetWorkflow(null)
                    .build();

            CompletionTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.sourceWorkflowId()).isNull();
            assertThat(response.sourceWorkflowName()).isNull();
            assertThat(response.targetWorkflowId()).isNull();
            assertThat(response.targetWorkflowName()).isNull();
        }

        @Test
        void triggerAlways_true_mappedCorrectly() {
            Workflow wf = Workflow.builder().id("w1").name("W").build();
            CompletionTrigger trigger = CompletionTrigger.builder()
                    .sourceWorkflow(wf)
                    .targetWorkflow(wf)
                    .triggerAlways(true)
                    .build();

            CompletionTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.triggerAlways()).isTrue();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        CompletionTrigger t1 = CompletionTrigger.builder().id("t1").sourceWorkflow(wf).targetWorkflow(wf).build();
        CompletionTrigger t2 = CompletionTrigger.builder().id("t2").sourceWorkflow(wf).targetWorkflow(wf).build();

        List<CompletionTriggerResponse> responses = mapper.toResponseList(List.of(t1, t2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("t1");
        assertThat(responses.get(1).id()).isEqualTo("t2");
    }
}
