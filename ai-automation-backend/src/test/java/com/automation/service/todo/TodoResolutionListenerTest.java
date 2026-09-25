package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoResolutionListenerTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private GateStatusResolver gateStatusResolver;

    private TodoResolutionListener listener;

    private static final String RUN_ID = "run-123";
    private static final String TODO_ID = "todo-abc";

    @BeforeEach
    void setUp() {
        listener = new TodoResolutionListener(todoRepository, gateStatusResolver);
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status, Map<String, Object> runMetadata) {
        return new WorkflowRunCompletedEvent(
                this,
                RUN_ID,
                "workflow-1",
                "Test Workflow",
                "project-1",
                "/tmp/project",
                status,
                runMetadata,
                new HashMap<>(),
                null
        );
    }

    private Todo createTodo() {
        Todo todo = new Todo();
        todo.setId(TODO_ID);
        todo.setTitle("Test Todo");
        todo.setLastFixRunId(RUN_ID);
        todo.setStatus(Todo.Status.IN_PROGRESS);
        todo.setResolutionState(Todo.ResolutionState.RUNNING);
        return todo;
    }

    @Test
    void should_doNothing_when_linkedTodoIdMissing() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, new HashMap<>());

        listener.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).findById(any());
        verify(todoRepository, never()).save(any());
    }

    @Test
    void should_doNothing_when_todoNotFound() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, meta);
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.empty());

        listener.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).save(any());
    }

    @Test
    void should_ignoreStaleRun_when_runIdDoesNotMatchLastFixRunId() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, meta);

        Todo todo = createTodo();
        todo.setLastFixRunId("different-run-id");
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).save(any());
    }

    @Test
    void should_markTodoAsDoneAndSucceeded_when_runCompletedWithGreenGate() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateStatusResolver.GateResult(GateStatus.GREEN, "ok", "all green"));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.DONE);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.SUCCEEDED);
    }

    @Test
    void should_markTodoAsDoneAndSucceeded_when_runCompletedAndGateIsNull() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(event)).thenReturn(null);

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.DONE);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.SUCCEEDED);
    }

    @Test
    void should_restorePreviousStatusAndMarkFailed_when_runFailed() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", "IN_PROGRESS");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
    }

    @Test
    void should_markFailed_when_gateIsYellow() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", "BACKLOG");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateStatusResolver.GateResult(GateStatus.YELLOW, "warn", "warnings"));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.BACKLOG);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
    }

    @Test
    void should_defaultToOpen_when_previousStatusIsInvalid() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", "NOT_A_REAL_STATUS");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
    }

    /** a9bf13d5: read like a request parameter (EnumParameter) — trimmed and in any case. */
    @Test
    void should_restorePreviousStatus_writtenInAnyCase() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", " backlog ");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.BACKLOG);
    }

    @Test
    void should_defaultToOpen_when_previousStatusIsMissing() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
    }

    @Test
    void should_defaultToOpen_when_previousStatusIsBlank() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", "   ");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.OPEN);
    }

    @Test
    void should_treatNonCompletedStatusAsFailure_evenWithGreenGate() {
        // RunStatus other than COMPLETED → isSuccessful returns false immediately, gate not consulted
        Map<String, Object> meta = new HashMap<>();
        meta.put("linkedTodoId", TODO_ID);
        meta.put("linkedTodoPreviousStatus", "IN_PROGRESS");
        WorkflowRunCompletedEvent event = createEvent(RunStatus.CANCELLED, meta);

        Todo todo = createTodo();
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));

        listener.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(captor.getValue().getResolutionState()).isEqualTo(Todo.ResolutionState.FAILED);
    }
}
