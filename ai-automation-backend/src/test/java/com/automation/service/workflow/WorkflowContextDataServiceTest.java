package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowContextDataServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepResultRepository stepResultRepository;
    @Mock private TodoRepository todoRepository;

    @InjectMocks private WorkflowContextDataService service;

    // --- loadOpenTodos -----------------------------------------------------------------

    @Test
    void loadOpenTodos_rendersIdPriorityTagsAndTitle() {
        Todo t = Todo.builder().id("t-1").title("Fix it").priority(Todo.Priority.HIGH).tags("backend, bug").build();
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of(t));

        assertThat(service.loadOpenTodos("proj-1")).isEqualTo("- t-1 [HIGH] [backend, bug] Fix it\n");
    }

    @Test
    void loadOpenTodos_withoutTags_omitsTagBracket() {
        Todo t = Todo.builder().id("t-1").title("Fix it").priority(Todo.Priority.LOW).build();
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of(t));

        assertThat(service.loadOpenTodos("proj-1")).isEqualTo("- t-1 [LOW] Fix it\n");
    }

    @Test
    void loadOpenTodos_none_returnsPlaceholder() {
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());

        assertThat(service.loadOpenTodos("proj-1")).isEqualTo("(keine offenen TODOs)");
    }

    @Test
    void loadOpenTodos_repositoryThrows_returnsUnavailablePlaceholder() {
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenThrow(new RuntimeException("db"));

        assertThat(service.loadOpenTodos("proj-1")).isEqualTo("(TODOs nicht verfügbar)");
    }

    // --- loadPreviousReport ------------------------------------------------------------

    @Test
    void loadPreviousReport_noCompletedRun_returnsPlaceholder() {
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        assertThat(service.loadPreviousReport("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }

    @Test
    void loadPreviousReport_skipsNonCompletedRuns() {
        WorkflowRun failed = WorkflowRun.builder().id("run-9").status(RunStatus.FAILED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(failed));

        assertThat(service.loadPreviousReport("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }

    @Test
    void loadPreviousReport_returnsLongestNonFirstCompletedStepOutput() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        String shellData = "x".repeat(5000);           // step 0 is skipped (data collection)
        String analysis = "analysis ".repeat(50);      // > 200 chars
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(shellData).build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output(analysis).build(),
                StepResult.builder().stepIndex(2).status(StepStatus.FAILED).output("y".repeat(9000)).build()));

        assertThat(service.loadPreviousReport("wf-1")).isEqualTo(analysis);
    }

    @Test
    void loadPreviousReport_truncatesAt3000Chars() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("data").build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("z".repeat(4000)).build()));

        String report = service.loadPreviousReport("wf-1");

        assertThat(report).hasSize(3000 + "\n[...gekürzt]".length()).endsWith("[...gekürzt]");
    }

    @Test
    void loadPreviousReport_shortOutputsAreIgnored() {
        WorkflowRun prev = WorkflowRun.builder().id("run-0").status(RunStatus.COMPLETED).build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(prev));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-0")).thenReturn(List.of(
                StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("data").build(),
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("too short").build()));

        assertThat(service.loadPreviousReport("wf-1")).isEqualTo("(kein vorheriger Report vorhanden)");
    }

    // --- injectPlatformData ------------------------------------------------------------

    @Test
    void injectPlatformData_setsBothVariables() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        when(runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndStatusIn(eq("proj-1"), anyList())).thenReturn(List.of());

        service.injectPlatformData(ctx, "wf-1", "proj-1");

        assertThat(ctx.getVariable("previous_report")).isEqualTo("(kein vorheriger Report vorhanden)");
        assertThat(ctx.getVariable("open_todos")).isEqualTo("(keine offenen TODOs)");
    }
}
