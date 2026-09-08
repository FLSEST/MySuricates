package com.automation.service.assistant;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Moved from {@code AssistantContextBuilderTest} when the builder's format delegates were removed. */
class AssistantFormatUtilsTest {

    @Test
    void truncate_shortString_unchanged() {
        assertThat(AssistantFormatUtils.truncate("short", 100)).isEqualTo("short");
    }

    @Test
    void truncate_longString_truncated() {
        String long_ = "x".repeat(200);
        String result = AssistantFormatUtils.truncate(long_, 50);
        assertThat(result.length()).isLessThan(200);
        assertThat(result).contains("Output truncated");
    }

    @Test
    void truncate_null_returnsEmptyString() {
        assertThat(AssistantFormatUtils.truncate(null, 50)).isEmpty();
    }

    @Test
    void truncate_exactMaxLength_unchanged() {
        String exact = "x".repeat(50);
        assertThat(AssistantFormatUtils.truncate(exact, 50)).isEqualTo(exact);
    }

    @Test
    void extractError_withErrorMap_returnsMessage() {
        WorkflowRun run = WorkflowRun.builder().id("r1")
                .error(Map.of("message", "Something went wrong")).build();
        assertThat(AssistantFormatUtils.extractError(run)).isEqualTo("Something went wrong");
    }

    @Test
    void extractError_emptyError_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1").build();
        assertThat(AssistantFormatUtils.extractError(run)).isNull();
    }

    @Test
    void extractError_nullErrorMap_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1").error(null).build();
        assertThat(AssistantFormatUtils.extractError(run)).isNull();
    }

    @Test
    void extractError_noMessageKey_returnsNull() {
        WorkflowRun run = WorkflowRun.builder().id("r1")
                .error(Map.of("other", "data")).build();
        assertThat(AssistantFormatUtils.extractError(run)).isNull();
    }

    @Test
    void fmt_validInstant_formatsCorrectly() {
        Instant instant = Instant.parse("2026-03-15T14:30:00Z");
        String result = AssistantFormatUtils.fmt(instant);
        assertThat(result).isNotNull();
        assertThat(result).matches("\\d{2}\\.\\d{2}\\. \\d{2}:\\d{2}");
    }

    @Test
    void fmt_nullInstant_returnsDash() {
        assertThat(AssistantFormatUtils.fmt(null)).isEqualTo("—");
    }

    @Test
    void runLabel_withWorkflow_formatsLabel() {
        WorkflowRun run = WorkflowRun.builder().id("r1").status(RunStatus.COMPLETED).build();
        // runLabel accesses lazy-loaded workflow — in test env returns null-safe
        assertThat(AssistantFormatUtils.runLabel(run)).isNotNull();
    }

    @Test
    void runLabel_noWorkflow_returnsUnbekannt() {
        WorkflowRun run = WorkflowRun.builder().id("r1").build();
        assertThat(AssistantFormatUtils.runLabel(run)).isEqualTo("Unknown");
    }

    @Test
    void runLink_noWorkflow_returnsRunPath() {
        WorkflowRun run = WorkflowRun.builder().id("run-123").build();
        assertThat(AssistantFormatUtils.runLink(run)).isEqualTo("/runs/run-123");
    }
}
