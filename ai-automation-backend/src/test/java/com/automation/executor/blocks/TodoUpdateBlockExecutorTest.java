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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoUpdateBlockExecutorTest {

    @Mock private TodoService todoService;

    @InjectMocks private TodoUpdateBlockExecutor executor;

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
    }

    private BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("todo-update").config(config).build();
    }

    @Test
    void supports_todoUpdateOnly() {
        assertThat(executor.supports("todo-update")).isTrue();
        assertThat(executor.supports("todo-create")).isFalse();
    }

    @Test
    void execute_updatesOnlyProvidedFields() {
        when(todoService.update(eq("todo-5"), any()))
                .thenReturn(Todo.builder().id("todo-5").title("Neu").build());

        Map<String, Object> config = new HashMap<>();
        config.put("id", "todo-5");
        config.put("title", "Neu");
        config.put("priority", "LOW");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).update(eq("todo-5"), argThat(req ->
                "Neu".equals(req.title())
                        && req.priority() == Todo.Priority.LOW
                        && req.description() == null
                        && req.tags() == null
                        && req.status() == null));
    }

    @Test
    void execute_idFromTemplateVariable_isResolved() {
        when(todoService.update(eq("todo-77"), any()))
                .thenReturn(Todo.builder().id("todo-77").title("T").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("todo_id", "todo-77");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "{{ todo_id }}");
        config.put("tags", List.of("triaged"));

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).update(eq("todo-77"), argThat(req -> req.tags().equals(List.of("triaged"))));
    }

    @Test
    void execute_priorityPlaceholder_isResolved() {
        when(todoService.update(eq("todo-77"), any()))
                .thenReturn(Todo.builder().id("todo-77").title("T").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("todo_id", "todo-77");
        ctx.setVariable("new_priority", "HIGH");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "{{ todo_id }}");
        config.put("priority", "{{ new_priority }}");

        executor.execute(block(config), ctx);

        verify(todoService).update(eq("todo-77"), argThat(req -> req.priority() == Todo.Priority.HIGH));
    }

    @Test
    void execute_missingId_failsWithoutServiceCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("title", "x");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("id");
        verify(todoService, never()).update(anyString(), any());
    }
}
