package com.automation.service.todo;

import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoResolutionServiceTest {

    @Mock private TodoService todoService;
    @Mock private TodoRepository todoRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private ObjectProvider<WorkflowExecutor> workflowExecutorProvider;
    @InjectMocks private TodoResolutionService resolutionService;

    @Test
    void resolve_linkedWorkflow_setsTodoInProgressAndStartsRun() {
        Todo todo = Todo.builder()
                .id("t-1")
                .title("Fix this")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM)
                .linkedWorkflowId("wf-1")
                .source(Todo.Source.USER)
                .build();
        Workflow workflow = Workflow.builder().id("wf-1").name("Resolver").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();

        when(todoService.findById("t-1")).thenReturn(todo);
        when(workflowService.findByIdEager("wf-1")).thenReturn(workflow);
        when(workflowExecutorProvider.getObject()).thenReturn(workflowExecutor);
        when(workflowExecutor.execute(eq(workflow), anyMap(), isNull(), anyMap())).thenReturn(run);
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowRun result = resolutionService.resolve("t-1");

        assertThat(result.getId()).isEqualTo("run-1");
        assertThat(todo.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(todo.getLastFixRunId()).isEqualTo("run-1");
        assertThat(todo.getResolutionState()).isEqualTo(Todo.ResolutionState.RUNNING);
    }

    @Test
    void resolve_withoutLinkedWorkflow_throws() {
        Todo todo = Todo.builder()
                .id("t-1")
                .title("Unlinked")
                .status(Todo.Status.OPEN)
                .build();
        when(todoService.findById("t-1")).thenReturn(todo);

        assertThatThrownBy(() -> resolutionService.resolve("t-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no linked workflow");
    }

    @Test
    void resolve_whileFixRunStillRunning_throws() {
        Todo todo = Todo.builder().id("t-1").title("x").priority(Todo.Priority.MEDIUM)
                .linkedWorkflowId("wf-1").status(Todo.Status.OPEN).lastFixRunId("run-9").build();
        when(todoService.findById("t-1")).thenReturn(todo);
        when(workflowRunRepository.findById("run-9"))
                .thenReturn(Optional.of(WorkflowRun.builder().id("run-9").status(RunStatus.RUNNING).build()));

        assertThatThrownBy(() -> resolutionService.resolve("t-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }
}
