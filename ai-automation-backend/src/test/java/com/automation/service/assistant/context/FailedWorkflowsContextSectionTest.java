package com.automation.service.assistant.context;

import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.service.execution.WorkflowDebugService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FailedWorkflowsContextSectionTest {

    @Mock
    private WorkflowDebugService workflowDebugService;

    @InjectMocks
    private FailedWorkflowsContextSection section;

    private BrokenWorkflowResponse broken(String workflowId, String workflowName, String runId,
                                          String errorMessage, Integer failedStepIndex, String failedBlockType) {
        return new BrokenWorkflowResponse(
                workflowId,
                workflowName,
                "Demo Project",
                runId,
                RunStatus.FAILED,
                errorMessage,
                failedStepIndex,
                failedBlockType,
                null,
                null,
                null,
                null
        );
    }

    @Test
    void should_renderFailedWorkflow_when_brokenWorkflowsExist() {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(
                broken("wf-1", "Nightly Backup", "run-42", "Disk full", 2, "shell")
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String out = sb.toString();
        assertThat(out)
                .contains("## Broken Workflows (last run failed)")
                .contains("1 workflow(s) with a red last run:")
                .contains("[Nightly Backup]")
                .contains("FAILED")
                .contains("step 3")
                .contains("(shell)")
                .contains("Disk full")
                .contains("(run:run-42")
                .contains("/workflows/wf-1/run/run-42")
                .contains("<fetch type=\"run_detail\" id=\"<run-id>\"/>");
    }

    @Test
    void should_showNoneMarker_when_listIsEmpty() {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("## Broken Workflows (last run failed)")
                .contains("None — all workflows ran cleanly on their last run.")
                .doesNotContain("with a red last run");
    }

    @Test
    void should_useUnknownName_when_workflowNameIsNull() {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(
                broken("wf-9", null, "run-9", null, null, null)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String out = sb.toString();
        assertThat(out)
                .contains("[Unknown]")
                .contains("(run:run-9")
                .doesNotContain("step ");
    }

    @Test
    void should_truncateLongErrorMessage_when_errorExceeds160Chars() {
        String longError = "E".repeat(200);
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(
                broken("wf-2", "Big Error WF", "run-2", longError, 0, "simple")
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String out = sb.toString();
        // Tail-biased since the diagnosis rework: keep the END of the message
        // (the actual error sits there), prefixed with an ellipsis.
        assertThat(out).contains("…");
        assertThat(out).doesNotContain("E".repeat(200));
        assertThat(out).contains("…" + "E".repeat(160));
    }

    @Test
    void should_omitBlockType_when_failedBlockTypeIsNullButStepPresent() {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(
                broken("wf-3", "No Block Type", "run-3", "boom", 4, null)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        String out = sb.toString();
        assertThat(out)
                .contains("step 5")
                .doesNotContain("step 5 (");
    }

    @Test
    void should_notWriteOutput_when_serviceThrows() {
        when(workflowDebugService.getBrokenWorkflows()).thenThrow(new RuntimeException("db down"));

        StringBuilder sb = new StringBuilder();
        assertThatCode(() -> section.append(sb, false)).doesNotThrowAnyException();

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void should_notCrash_when_serviceReturnsNull() {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(null);

        StringBuilder sb = new StringBuilder();
        assertThatCode(() -> section.append(sb, false)).doesNotThrowAnyException();

        assertThat(sb.toString()).contains("## Broken Workflows (last run failed)");
        assertThat(sb.toString()).doesNotContain("None —");
    }
}
