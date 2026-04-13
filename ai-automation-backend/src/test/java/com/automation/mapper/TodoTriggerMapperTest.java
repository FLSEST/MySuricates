package com.automation.mapper;

import com.automation.dto.response.TodoTriggerResponse;
import com.automation.entity.TodoTrigger;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoTriggerMapperTest {

    private TodoTriggerMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new TodoTriggerMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            Workflow wf = Workflow.builder().id("wf-1").name("Fix Workflow").build();

            TodoTrigger trigger = TodoTrigger.builder()
                    .id("tt-1")
                    .tagPattern("bug")
                    .projectId("proj-42")
                    .sourceFilter("AI")
                    .minPriority("HIGH")
                    .targetWorkflow(wf)
                    .contextTemplate(Map.of("mode", "auto"))
                    .enabled(true)
                    .maxAttempts(5)
                    .autoClose(true)
                    .processBacklog(false)
                    .description("Trigger for bugs")
                    .lastTriggeredAt(now)
                    .triggerCount(3)
                    .createdAt(now)
                    .build();

            TodoTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.id()).isEqualTo("tt-1");
            assertThat(response.tagPattern()).isEqualTo("bug");
            assertThat(response.projectId()).isEqualTo("proj-42");
            assertThat(response.sourceFilter()).isEqualTo("AI");
            assertThat(response.minPriority()).isEqualTo("HIGH");
            assertThat(response.targetWorkflowId()).isEqualTo("wf-1");
            assertThat(response.targetWorkflowName()).isEqualTo("Fix Workflow");
            assertThat(response.contextTemplate()).containsEntry("mode", "auto");
            assertThat(response.enabled()).isTrue();
            assertThat(response.maxAttempts()).isEqualTo(5);
            assertThat(response.autoClose()).isTrue();
            assertThat(response.processBacklog()).isFalse();
            assertThat(response.description()).isEqualTo("Trigger for bugs");
            assertThat(response.lastTriggeredAt()).isEqualTo(now);
            assertThat(response.triggerCount()).isEqualTo(3);
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void nullTargetWorkflow_idNullAndNameIsDeleted() {
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("tt-2")
                    .tagPattern("feature")
                    .targetWorkflow(null)
                    .build();

            TodoTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.targetWorkflowId()).isNull();
            assertThat(response.targetWorkflowName()).isEqualTo("[gelöscht]");
        }

        @Test
        void withTargetWorkflow_propagatesNameAndId() {
            Workflow wf = Workflow.builder().id("wf-99").name("Deploy").build();
            TodoTrigger trigger = TodoTrigger.builder()
                    .id("tt-3")
                    .tagPattern("deploy")
                    .targetWorkflow(wf)
                    .build();

            TodoTriggerResponse response = mapper.toResponse(trigger);

            assertThat(response.targetWorkflowId()).isEqualTo("wf-99");
            assertThat(response.targetWorkflowName()).isEqualTo("Deploy");
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
        TodoTrigger t1 = TodoTrigger.builder().id("t1").tagPattern("a").targetWorkflow(wf).build();
        TodoTrigger t2 = TodoTrigger.builder().id("t2").tagPattern("b").targetWorkflow(wf).build();

        List<TodoTriggerResponse> responses = mapper.toResponseList(List.of(t1, t2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("t1");
        assertThat(responses.get(1).id()).isEqualTo("t2");
    }
}
