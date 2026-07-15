package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.executor.ExecutionContext;
import com.automation.service.todo.TodoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoCreateBlockExecutorTest {

    @Mock private TodoService todoService;

    @InjectMocks private TodoCreateBlockExecutor executor;

    private ExecutionContext buildContext() {
        ExecutionContext ctx = ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
        ctx.setVariable("project_id", "proj-1");
        return ctx;
    }

    private BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("todo-create").config(config).build();
    }

    @Test
    void supports_todoCreateOnly() {
        assertThat(executor.supports("todo-create")).isTrue();
        assertThat(executor.supports("todo-update")).isFalse();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_createsTodo_withResolvedTemplateAndDefaults() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("todo-9").title("Fix Login").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("component", "Login");
        Map<String, Object> config = new HashMap<>();
        config.put("title", "Fix {{ component }}");
        config.put("description", "Kaputt in {{ component }}");
        config.put("tags", List.of("bug", "backend"));

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("todo-9");
        verify(todoService).create(argThat(req ->
                "Fix Login".equals(req.title())
                        && "Kaputt in Login".equals(req.description())
                        && req.priority() == Todo.Priority.MEDIUM
                        && req.tags().equals(List.of("bug", "backend"))
                        && "proj-1".equals(req.projectId())
                        && req.source() == Todo.Source.WORKFLOW
                        && "run-1".equals(req.workflowRunId())));
        // Die neue TODO-ID steht Folge-Blöcken als Variable zur Verfügung
        assertThat(ctx.getVariable("todo_id")).isEqualTo("todo-9");
    }

    @Test
    void execute_explicitProjectIdAndPriority_overrideDefaults() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("todo-1").title("T").build());

        Map<String, Object> config = new HashMap<>();
        config.put("title", "T");
        config.put("priority", "HIGH");
        config.put("project_id", "proj-other");
        config.put("variable_name", "created_todo");

        ExecutionContext ctx = buildContext();
        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).create(argThat(req ->
                req.priority() == Todo.Priority.HIGH && "proj-other".equals(req.projectId())));
        assertThat(ctx.getVariable("created_todo")).isEqualTo("todo-1");
    }

    @Test
    void execute_missingTitle_failsWithoutServiceCall() {
        StepResult result = executor.execute(block(new HashMap<>()), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("title");
        verify(todoService, never()).create(any());
    }

    @Test
    void execute_serviceThrows_returnsFailed() {
        when(todoService.create(any())).thenThrow(new IllegalArgumentException("Projekt nicht gefunden"));

        Map<String, Object> config = new HashMap<>();
        config.put("title", "T");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Projekt nicht gefunden");
    }
}
