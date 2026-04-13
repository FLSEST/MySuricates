package com.automation.mapper;

import com.automation.dto.response.WorkflowMemoryResponse;
import com.automation.entity.WorkflowMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowMemoryMapperTest {

    private WorkflowMemoryMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new WorkflowMemoryMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllBasicFields() {
            Instant now = Instant.now();
            WorkflowMemory memory = WorkflowMemory.builder()
                    .id("mem-1")
                    .workflowId("wf-1")
                    .title("Test Title")
                    .content("Test content")
                    .tags("[\"ai\",\"test\"]")
                    .sourceRunIds("[\"run-1\",\"run-2\"]")
                    .provider("anthropic")
                    .active(true)
                    .createdAt(now)
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.id()).isEqualTo("mem-1");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.title()).isEqualTo("Test Title");
            assertThat(response.content()).isEqualTo("Test content");
            assertThat(response.provider()).isEqualTo("anthropic");
            assertThat(response.active()).isTrue();
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void parsesTagsJsonToList() {
            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-1")
                    .content("content")
                    .provider("anthropic")
                    .tags("[\"tag1\",\"tag2\",\"tag3\"]")
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.tags()).containsExactly("tag1", "tag2", "tag3");
        }

        @Test
        void parsesSourceRunIdsJsonToList() {
            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-1")
                    .content("content")
                    .provider("anthropic")
                    .sourceRunIds("[\"run-a\",\"run-b\"]")
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.sourceRunIds()).containsExactly("run-a", "run-b");
        }

        @Test
        void nullTags_returnsEmptyList() {
            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-1")
                    .content("content")
                    .provider("anthropic")
                    .tags(null)
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.tags()).isEmpty();
        }

        @Test
        void blankTags_returnsEmptyList() {
            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-1")
                    .content("content")
                    .provider("anthropic")
                    .tags("   ")
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.tags()).isEmpty();
        }

        @Test
        void invalidJsonTags_returnsEmptyList() {
            WorkflowMemory memory = WorkflowMemory.builder()
                    .workflowId("wf-1")
                    .content("content")
                    .provider("anthropic")
                    .tags("not-valid-json")
                    .build();

            WorkflowMemoryResponse response = mapper.toResponse(memory);

            assertThat(response.tags()).isEmpty();
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(mapper.toResponse(null)).isNull();
        }
    }

    @Nested
    class JsonToList {

        @Test
        void nullJson_returnsEmptyList() {
            assertThat(WorkflowMemoryMapper.jsonToList(null)).isEmpty();
        }

        @Test
        void blankJson_returnsEmptyList() {
            assertThat(WorkflowMemoryMapper.jsonToList("")).isEmpty();
            assertThat(WorkflowMemoryMapper.jsonToList("  ")).isEmpty();
        }

        @Test
        void validJson_returnsList() {
            List<String> result = WorkflowMemoryMapper.jsonToList("[\"a\",\"b\",\"c\"]");
            assertThat(result).containsExactly("a", "b", "c");
        }

        @Test
        void emptyJsonArray_returnsEmptyList() {
            assertThat(WorkflowMemoryMapper.jsonToList("[]")).isEmpty();
        }

        @Test
        void invalidJson_returnsEmptyList() {
            assertThat(WorkflowMemoryMapper.jsonToList("{invalid}")).isEmpty();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        WorkflowMemory m1 = WorkflowMemory.builder()
                .id("mem-1").workflowId("wf-1").content("c1").provider("p1").build();
        WorkflowMemory m2 = WorkflowMemory.builder()
                .id("mem-2").workflowId("wf-2").content("c2").provider("p2").build();

        List<WorkflowMemoryResponse> result = mapper.toResponseList(List.of(m1, m2));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("mem-1");
        assertThat(result.get(1).id()).isEqualTo("mem-2");
    }
}
