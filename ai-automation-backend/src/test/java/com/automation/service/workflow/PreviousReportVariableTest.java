package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** {@code previous_report} (TODO 441a4c46: aus WorkflowContextDataServiceTest hierher gezogen, wie der Lader). */
@ExtendWith(MockitoExtension.class)
class PreviousReportVariableTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepResultRepository stepResultRepository;

    @InjectMocks private PreviousReportVariable previousReport;

    @Test
    void value_noCompletedRun_returnsPlaceholder() {
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        assertThat(previousReport.value("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }

    @Test
    void value_skipsNonCompletedRuns() {
        WorkflowRun failed = WorkflowRun.builder().id("run-9").status(RunStatus.FAILED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(failed));

        assertThat(previousReport.value("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }

    @Test
    void value_returnsLongestNonFirstCompletedStepOutput() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        String shellData = "x".repeat(5000);           // step 0 is skipped (data collection)
        String analysis = "analysis ".repeat(50);      // > 200 chars
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(shellData).build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output(analysis).build(),
                StepResult.builder().stepIndex(2).status(StepStatus.FAILED).output("y".repeat(9000)).build()));

        assertThat(previousReport.value("wf-1")).isEqualTo(analysis);
    }

    @Test
    void value_truncatesAt3000Chars() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("data").build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("z".repeat(4000)).build()));

        String report = previousReport.value("wf-1");

        assertThat(report).hasSize(3000 + "\n[...gekürzt]".length()).endsWith("[...gekürzt]");
    }

    @Test
    void value_shortOutputsAreIgnored() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("data").build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("too short").build()));

        assertThat(previousReport.value("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }
}
