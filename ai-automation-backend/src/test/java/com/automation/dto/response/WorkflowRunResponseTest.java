package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import com.automation.mapper.WorkflowMapperImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowRunResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var step = mock(StepResultResponse.class);
        var startedAt = Instant.parse("2026-01-15T10:00:00Z");
        var completedAt = Instant.parse("2026-01-15T10:05:00Z");
        Map<String, Object> error = Map.of("message", "Something failed");
        Map<String, Object> metadata = Map.of("version", "1.0");
        Map<String, Object> inputContext = Map.of("key", "value");

        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "My Workflow", "My Project", "/path/to/project",
                RunStatus.COMPLETED, List.of(step), startedAt, completedAt,
                300000L, 5, error, metadata, inputContext, "schedule-1"
        );

        assertThat(response.id()).isEqualTo("run-1");
        assertThat(response.workflowId()).isEqualTo("wf-1");
        assertThat(response.workflowName()).isEqualTo("My Workflow");
        assertThat(response.projectName()).isEqualTo("My Project");
        assertThat(response.projectPath()).isEqualTo("/path/to/project");
        assertThat(response.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(response.steps()).containsExactly(step);
        assertThat(response.startedAt()).isEqualTo(startedAt);
        assertThat(response.completedAt()).isEqualTo(completedAt);
        assertThat(response.durationMs()).isEqualTo(300000L);
        assertThat(response.userRating()).isEqualTo(5);
        assertThat(response.error()).isEqualTo(error);
        assertThat(response.metadata()).isEqualTo(metadata);
        assertThat(response.inputContext()).isEqualTo(inputContext);
        assertThat(response.triggeredByScheduleId()).isEqualTo("schedule-1");
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new WorkflowRunResponse(
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null
        );

        assertThat(response.id()).isNull();
        assertThat(response.workflowId()).isNull();
        assertThat(response.workflowName()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.projectPath()).isNull();
        assertThat(response.status()).isNull();
        assertThat(response.steps()).isNull();
        assertThat(response.startedAt()).isNull();
        assertThat(response.completedAt()).isNull();
        assertThat(response.durationMs()).isNull();
        assertThat(response.userRating()).isNull();
        assertThat(response.error()).isNull();
        assertThat(response.metadata()).isNull();
        assertThat(response.inputContext()).isNull();
        assertThat(response.triggeredByScheduleId()).isNull();
    }

    // --- chain run (TODO a3b89355) ---

    @Test
    void should_carryTheChainRun_onlyWhenTheRunRecordedOne() {
        var inChain = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.COMPLETED, List.of(),
                null, null, null, null, null, null, null, null, "hash", "ver-1", TriggerSource.CHAIN, false, null,
                "chain-run-7");
        var beforeChainRuns = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.COMPLETED, List.of(),
                null, null, null, null, null, null, null, null, "hash", "ver-1", TriggerSource.CHAIN, false, null);

        assertThat(inChain.chainRunId()).isEqualTo("chain-run-7");
        assertThat(beforeChainRuns.chainRunId()).isNull();
        assertThat(beforeChainRuns.triggerSource()).isEqualTo(TriggerSource.CHAIN);
    }

    @Test
    void should_takeTheChainRunFromTheRun_when_mapped() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED)
                .triggerSource(TriggerSource.CHAIN).chainRunId("chain-run-7").build();

        WorkflowRunResponse response = new WorkflowMapperImpl().toRunResponse(run);

        assertThat(response.chainRunId()).isEqualTo("chain-run-7");
        assertThat(response.triggerSource()).isEqualTo(TriggerSource.CHAIN);
        assertThat(new WorkflowMapperImpl().toRunResponse(WorkflowRun.builder().id("run-2").build()).chainRunId()).isNull();
    }

    @Test
    void should_returnEmptySteps_when_constructedWithEmptyList() {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.PENDING, List.of(), null, null,
                null, null, null, null, null, null
        );

        assertThat(response.steps()).isEmpty();
    }

    @Test
    void should_returnMultipleSteps_when_constructedWithMultipleSteps() {
        var step1 = mock(StepResultResponse.class);
        var step2 = mock(StepResultResponse.class);
        var step3 = mock(StepResultResponse.class);

        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.RUNNING, List.of(step1, step2, step3), null, null,
                null, null, null, null, null, null
        );

        assertThat(response.steps()).hasSize(3).containsExactly(step1, step2, step3);
    }

    @Test
    void should_acceptDifferentStatuses_when_constructed() {
        for (RunStatus status : RunStatus.values()) {
            var response = new WorkflowRunResponse(
                    "run-1", "wf-1", "Workflow", null, null,
                    status, null, null, null,
                    null, null, null, null, null, null
            );

            assertThat(response.status()).isEqualTo(status);
        }
    }

    @Test
    void should_acceptEmptyMaps_when_constructed() {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.COMPLETED, null, null, null,
                null, null, Map.of(), Map.of(), Map.of(), null
        );

        assertThat(response.error()).isEmpty();
        assertThat(response.metadata()).isEmpty();
        assertThat(response.inputContext()).isEmpty();
    }

    @Test
    void should_preserveNestedErrorMap_when_constructedWithComplexError() {
        Map<String, Object> error = Map.of(
                "message", "Execution failed",
                "code", 500,
                "details", Map.of("step", "block-3", "cause", "timeout")
        );

        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.FAILED, null, null, null,
                null, null, error, null, null, null
        );

        assertThat(response.error()).containsKey("message");
        assertThat(response.error()).containsKey("code");
        assertThat(response.error()).containsKey("details");
        assertThat(response.error().get("message")).isEqualTo("Execution failed");
        assertThat(response.error().get("code")).isEqualTo(500);
    }

    @Test
    void should_acceptZeroDuration_when_constructed() {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.COMPLETED, null, null, null,
                0L, null, null, null, null, null
        );

        assertThat(response.durationMs()).isZero();
    }

    @Test
    void should_acceptZeroRating_when_constructed() {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", null, null,
                RunStatus.COMPLETED, null, null, null,
                null, 0, null, null, null, null
        );

        assertThat(response.userRating()).isZero();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var startedAt = Instant.parse("2026-01-15T10:00:00Z");
        Map<String, Object> metadata = Map.of("key", "value");

        var a = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", "Project", "/path",
                RunStatus.COMPLETED, List.of(), startedAt, null,
                1000L, 4, null, metadata, null, "sched-1"
        );
        var b = new WorkflowRunResponse(
                "run-1", "wf-1", "Workflow", "Project", "/path",
                RunStatus.COMPLETED, List.of(), startedAt, null,
                1000L, 4, null, metadata, null, "sched-1"
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new WorkflowRunResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var b = new WorkflowRunResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.COMPLETED, null, null, null, null, null, null, null, null, null);
        var b = new WorkflowRunResponse("run-2", "wf-1", "W", null, null, RunStatus.COMPLETED, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflowId() {
        var a = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.COMPLETED, null, null, null, null, null, null, null, null, null);
        var b = new WorkflowRunResponse("run-1", "wf-2", "W", null, null, RunStatus.COMPLETED, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.COMPLETED, null, null, null, null, null, null, null, null, null);
        var b = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, RunStatus.FAILED, null, null, null, null, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDuration() {
        var a = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, null, null, null, null, 1000L, null, null, null, null, null);
        var b = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, null, null, null, null, 2000L, null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTriggeredByScheduleId() {
        var a = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, null, null, null, null, null, null, null, null, null, "sched-1");
        var b = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, null, null, null, null, null, null, null, null, null, "sched-2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new WorkflowRunResponse("run-1", "wf-1", "W", null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new WorkflowRunResponse(
                "run-1", "wf-1", "My Workflow", "My Project", "/path",
                RunStatus.COMPLETED, List.of(), Instant.parse("2026-01-15T10:00:00Z"), null,
                5000L, 3, null, null, null, "sched-abc"
        );
        var str = response.toString();

        assertThat(str).contains("run-1");
        assertThat(str).contains("wf-1");
        assertThat(str).contains("My Workflow");
        assertThat(str).contains("My Project");
        assertThat(str).contains("COMPLETED");
        assertThat(str).contains("5000");
        assertThat(str).contains("sched-abc");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new WorkflowRunResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
