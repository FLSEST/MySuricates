package com.automation.mapper;

import com.automation.dto.response.ActivityLogResponse;
import com.automation.entity.ActivityLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityLogMapperTest {

    private ActivityLogMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ActivityLogMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            ActivityLog log = ActivityLog.builder()
                    .id("log-1")
                    .timestamp(now)
                    .level(ActivityLog.LogLevel.ERROR)
                    .category(ActivityLog.LogCategory.EXECUTION)
                    .message("Something went wrong")
                    .source("WorkflowExecutor")
                    .referenceId("run-42")
                    .workflowId("wf-1")
                    .metadata(Map.of("key", "value"))
                    .createdAt(now)
                    .build();

            ActivityLogResponse response = mapper.toResponse(log);

            assertThat(response.id()).isEqualTo("log-1");
            assertThat(response.timestamp()).isEqualTo(now);
            assertThat(response.level()).isEqualTo("ERROR");
            assertThat(response.category()).isEqualTo("EXECUTION");
            assertThat(response.message()).isEqualTo("Something went wrong");
            assertThat(response.source()).isEqualTo("WorkflowExecutor");
            assertThat(response.referenceId()).isEqualTo("run-42");
            assertThat(response.workflowId()).isEqualTo("wf-1");
            assertThat(response.metadata()).containsEntry("key", "value");
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void enumLevel_mappedToString() {
            ActivityLog log = ActivityLog.builder()
                    .level(ActivityLog.LogLevel.WARN)
                    .category(ActivityLog.LogCategory.SECURITY)
                    .message("warn")
                    .build();

            ActivityLogResponse response = mapper.toResponse(log);

            assertThat(response.level()).isEqualTo("WARN");
            assertThat(response.category()).isEqualTo("SECURITY");
        }

        @Test
        void nullLevel_mappedToNull() {
            ActivityLog log = ActivityLog.builder()
                    .message("msg")
                    .build();

            ActivityLogResponse response = mapper.toResponse(log);

            assertThat(response.level()).isNull();
            assertThat(response.category()).isNull();
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            ActivityLog log = ActivityLog.builder()
                    .id("log-2")
                    .message("minimal")
                    .build();

            ActivityLogResponse response = mapper.toResponse(log);

            assertThat(response.source()).isNull();
            assertThat(response.referenceId()).isNull();
            assertThat(response.workflowId()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        ActivityLog l1 = ActivityLog.builder().id("l1").message("a").level(ActivityLog.LogLevel.INFO).category(ActivityLog.LogCategory.SYSTEM).build();
        ActivityLog l2 = ActivityLog.builder().id("l2").message("b").level(ActivityLog.LogLevel.DEBUG).category(ActivityLog.LogCategory.CONTAINER).build();

        List<ActivityLogResponse> responses = mapper.toResponseList(List.of(l1, l2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("l1");
        assertThat(responses.get(1).id()).isEqualTo("l2");
    }
}
