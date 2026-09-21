package com.automation.service.assistant.fetch;

import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunFetchHandlerTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @InjectMocks private RunFetchHandler handler;

    private void stubRun(String id, StepResult... steps) {
        WorkflowRun run = WorkflowRun.builder().id(id).status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(steps))).build();
        when(workflowRunRepository.findByIdWithSteps(id)).thenReturn(Optional.of(run));
    }

    @Test
    void runDetail_labelsWorkflowWithProject_andShortensId() {
        Workflow wf = Workflow.builder().id("wf-1").name("Nightly")
                .project(Project.builder().id("p1").name("Alpha").build()).build();
        WorkflowRun run = WorkflowRun.builder().id("0123456789abcdef").workflow(wf)
                .status(RunStatus.COMPLETED).durationMs(61_999L).build();
        when(workflowRunRepository.findByIdWithSteps("0123456789abcdef")).thenReturn(Optional.of(run));

        assertThat(handler.formatRunDetail("0123456789abcdef"))
                .isEqualTo("**Run 01234567…** — Nightly (Alpha), Status: COMPLETED (61s)\n");
    }

    @Test
    void runDetail_failedStepWithoutOutput_showsOnlyStatus() {
        stubRun("r1", StepResult.builder().stepIndex(0).status(StepStatus.FAILED).output(null).build());

        assertThat(handler.formatRunDetail("r1")).endsWith("Steps:\n  1. FAILED\n");
    }

    @Test
    void runDetail_repositoryFailure_returnsEmpty() {
        when(workflowRunRepository.findByIdWithSteps("r1")).thenThrow(new IllegalStateException("db"));

        assertThat(handler.formatRunDetail("r1")).isEmpty();
    }

    @Test
    void workflowRuns_onlyFailedRunsCarryTheErrorSuffix() {
        WorkflowRun ok = WorkflowRun.builder().id("a").status(RunStatus.COMPLETED)
                .error(Map.of("message", "ignored for completed runs")).build();
        WorkflowRun failed = WorkflowRun.builder().id("b").status(RunStatus.FAILED)
                .error(Map.of("message", "exit 1")).build();
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-123456789"))
                .thenReturn(List.of(ok, failed));

        String out = handler.formatWorkflowRuns("wf-123456789");

        assertThat(out)
                .startsWith("**Recent runs for workflow wf-12345…:**\n")
                .contains("  - [—] COMPLETED (link:/runs/a)\n")
                .contains("  - [—] FAILED (link:/runs/b) — exit 1\n")
                .doesNotContain("ignored for completed runs");
    }

    @Test
    void workflowRuns_repositoryFailure_returnsEmpty() {
        when(workflowRunRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf")).thenThrow(new RuntimeException());

        assertThat(handler.formatWorkflowRuns("wf")).isEmpty();
    }

    @Test
    void stepOutput_runIdMayContainSlashes_lastSegmentIsTheStep() {
        stubRun("a/b", StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .durationMs(2_500L).output("done").build());

        assertThat(handler.formatStepOutput("a/b/2")).isEqualTo("**Step 2** (COMPLETED) — 2s\ndone\n");
    }

    @Test
    void stepOutput_tailIsCappedAt8000() {
        stubRun("r1", StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output("x".repeat(9000) + "END").build());

        String out = handler.formatStepOutput("r1/1?tail=50000");

        assertThat(out).contains("[... skipped 1003 of 9003 characters ...]").endsWith("END\n");
    }

    @Test
    void stepOutput_malformedPagingValues_areRejectedBeforeLookup() {
        assertThat(handler.formatStepOutput("r1/1?offset=abc")).startsWith("Invalid paging parameter: offset=abc.");
        assertThat(handler.formatStepOutput("r1/1?limit=5")).startsWith("Invalid paging parameter: limit=5.");
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    void stepOutput_blankOutput_countsAsNoOutput() {
        stubRun("r1", StepResult.builder().stepIndex(0).status(StepStatus.SKIPPED).output("   ").build());

        assertThat(handler.formatStepOutput("r1/1")).isEqualTo("**Step 1** (SKIPPED)\n(No output)\n");
    }
}
