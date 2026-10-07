package com.automation.service.todo;

import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoFixRunsTest {

    @Mock private WorkflowRunRepository workflowRunRepository;

    private static Todo todoWithFixRun(String runId) {
        return Todo.builder().id("t-1").lastFixRunId(runId).build();
    }

    private void runWithStatus(RunStatus status) {
        when(workflowRunRepository.findById("run-1"))
                .thenReturn(Optional.of(WorkflowRun.builder().id("run-1").status(status).build()));
    }

    @Test
    void noOrBlankFixRun_isNotRunning_withoutLookup() {
        assertThat(TodoFixRuns.isStillRunning(todoWithFixRun(null), workflowRunRepository)).isFalse();
        assertThat(TodoFixRuns.isStillRunning(todoWithFixRun("  "), workflowRunRepository)).isFalse();
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    void runningOrPendingRun_isStillRunning() {
        runWithStatus(RunStatus.RUNNING);
        assertThat(TodoFixRuns.isStillRunning(todoWithFixRun("run-1"), workflowRunRepository)).isTrue();

        runWithStatus(RunStatus.PENDING);
        assertThat(TodoFixRuns.isStillRunning(todoWithFixRun("run-1"), workflowRunRepository)).isTrue();
    }

    @Test
    void finishedRun_isNotRunning() {
        for (RunStatus finished : new RunStatus[] {RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED}) {
            runWithStatus(finished);
            assertThat(TodoFixRuns.isStillRunning(todoWithFixRun("run-1"), workflowRunRepository))
                    .as(finished.name()).isFalse();
        }
    }

    @Test
    void deletedRun_isNotRunning() {
        when(workflowRunRepository.findById("run-1")).thenReturn(Optional.empty());

        assertThat(TodoFixRuns.isStillRunning(todoWithFixRun("run-1"), workflowRunRepository)).isFalse();
    }
}
