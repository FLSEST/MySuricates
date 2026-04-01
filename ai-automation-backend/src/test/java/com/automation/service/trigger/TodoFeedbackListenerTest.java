package com.automation.service.trigger;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.Workflow;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.todo.GateStatusResolver;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoFeedbackListenerTest {

    @Mock private TodoRepository todoRepository;
    @Mock private TodoTriggerRepository todoTriggerRepository;
    @Mock private GateStatusResolver gateStatusResolver;
    @Mock private ActivityLogService activityLogService;

    @InjectMocks
    private TodoFeedbackListener listener;

    @Test
    void greenGate_autoCloseTrue_closesTodo() {
        Workflow targetWf = Workflow.builder().id("wf-1").build();
        TodoTrigger trigger = TodoTrigger.builder()
                .id("trig-1").autoClose(true).targetWorkflow(targetWf).build();
        Todo todo = Todo.builder()
                .id("todo-1").title("Fix it").status(Todo.Status.OPEN).fixAttempts(1).build();

        when(todoTriggerRepository.findById("trig-1")).thenReturn(Optional.of(trigger));
        when(todoRepository.findById("todo-1")).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(any())).thenReturn(new GateResult(GateStatus.GREEN, "success", "OK"));

        WorkflowRunCompletedEvent event = createEvent(Map.of(
                "todoTriggerId", "trig-1", "todoId", "todo-1"));

        listener.onWorkflowRunCompleted(event);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(todo);
    }

    @Test
    void greenGate_autoCloseFalse_leavesTodoOpen() {
        Workflow targetWf = Workflow.builder().id("wf-1").build();
        TodoTrigger trigger = TodoTrigger.builder()
                .id("trig-1").autoClose(false).targetWorkflow(targetWf).build();
        Todo todo = Todo.builder()
                .id("todo-1").title("Fix it").status(Todo.Status.OPEN).fixAttempts(1).build();

        when(todoTriggerRepository.findById("trig-1")).thenReturn(Optional.of(trigger));
        when(todoRepository.findById("todo-1")).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(any())).thenReturn(new GateResult(GateStatus.GREEN, "success", "OK"));

        listener.onWorkflowRunCompleted(createEvent(Map.of(
                "todoTriggerId", "trig-1", "todoId", "todo-1")));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(todoRepository, never()).save(any());
    }

    @Test
    void redGate_maxAttemptsExhausted_logsWarning() {
        Workflow targetWf = Workflow.builder().id("wf-1").build();
        TodoTrigger trigger = TodoTrigger.builder()
                .id("trig-1").autoClose(true).maxAttempts(3).targetWorkflow(targetWf).build();
        Todo todo = Todo.builder()
                .id("todo-1").title("Fix it").status(Todo.Status.OPEN).fixAttempts(3).build();

        when(todoTriggerRepository.findById("trig-1")).thenReturn(Optional.of(trigger));
        when(todoRepository.findById("todo-1")).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(any())).thenReturn(new GateResult(GateStatus.RED, "failure", "Error"));

        listener.onWorkflowRunCompleted(createEvent(Map.of(
                "todoTriggerId", "trig-1", "todoId", "todo-1")));

        verify(activityLogService).warn(any(), contains("exhausted"), any(), any(), any());
    }

    @Test
    void nonTodoTriggerRun_ignored() {
        listener.onWorkflowRunCompleted(createEvent(Map.of()));

        verifyNoInteractions(todoRepository);
        verifyNoInteractions(todoTriggerRepository);
    }

    @Test
    void todoAlreadyDone_noUpdate() {
        Workflow targetWf = Workflow.builder().id("wf-1").build();
        TodoTrigger trigger = TodoTrigger.builder()
                .id("trig-1").autoClose(true).targetWorkflow(targetWf).build();
        Todo todo = Todo.builder()
                .id("todo-1").title("Done already").status(Todo.Status.DONE).build();

        when(todoTriggerRepository.findById("trig-1")).thenReturn(Optional.of(trigger));
        when(todoRepository.findById("todo-1")).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(createEvent(Map.of(
                "todoTriggerId", "trig-1", "todoId", "todo-1")));

        verify(todoRepository, never()).save(any());
    }

    @Test
    void missingTriggerOrTodo_handledGracefully() {
        when(todoTriggerRepository.findById("trig-x")).thenReturn(Optional.empty());
        when(todoRepository.findById("todo-x")).thenReturn(Optional.empty());

        listener.onWorkflowRunCompleted(createEvent(Map.of(
                "todoTriggerId", "trig-x", "todoId", "todo-x")));

        // No exception, no saves
        verify(todoRepository, never()).save(any());
    }

    private WorkflowRunCompletedEvent createEvent(Map<String, Object> runMetadata) {
        Map<String, Object> meta = new HashMap<>(runMetadata);
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow",
                "proj-1", "/path", RunStatus.COMPLETED,
                meta, Map.of(), null);
    }
}
