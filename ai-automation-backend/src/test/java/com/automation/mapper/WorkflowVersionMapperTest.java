package com.automation.mapper;

import com.automation.dto.response.WorkflowVersionResponse;
import com.automation.entity.WorkflowVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVersionMapperTest {

    private WorkflowVersionMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WorkflowVersionMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            WorkflowVersion version = WorkflowVersion.builder()
                    .id("v-1")
                    .workflowId("wf-1")
                    .versionNumber(3)
                    .snapshot("{\"blocks\":[]}")
                    .changeSource("user")
                    .changeMessage("Added retry block")
                    .createdAt(now)
                    .build();

            WorkflowVersionResponse response = mapper.toResponse(version);

            assertThat(response.id()).isEqualTo("v-1");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.versionNumber()).isEqualTo(3);
            assertThat(response.snapshot()).isEqualTo("{\"blocks\":[]}");
            assertThat(response.changeSource()).isEqualTo("user");
            assertThat(response.changeMessage()).isEqualTo("Added retry block");
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void nullChangeMessage_mappedAsNull() {
            WorkflowVersion version = WorkflowVersion.builder()
                    .id("v-2")
                    .workflowId("wf-2")
                    .versionNumber(1)
                    .snapshot("{}")
                    .changeSource("auto-save")
                    .changeMessage(null)
                    .createdAt(Instant.now())
                    .build();

            WorkflowVersionResponse response = mapper.toResponse(version);

            assertThat(response.changeMessage()).isNull();
        }

        @Test
        void defaultChangeSource_isUser() {
            WorkflowVersion version = WorkflowVersion.builder()
                    .id("v-3")
                    .workflowId("wf-3")
                    .versionNumber(1)
                    .snapshot("{}")
                    .build();

            WorkflowVersionResponse response = mapper.toResponse(version);

            assertThat(response.changeSource()).isEqualTo("user");
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        WorkflowVersion v1 = WorkflowVersion.builder().id("v1").workflowId("wf-1").versionNumber(1).snapshot("{}").build();
        WorkflowVersion v2 = WorkflowVersion.builder().id("v2").workflowId("wf-1").versionNumber(2).snapshot("{}").build();

        List<WorkflowVersionResponse> responses = mapper.toResponseList(List.of(v1, v2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("v1");
        assertThat(responses.get(1).id()).isEqualTo("v2");
    }
}
