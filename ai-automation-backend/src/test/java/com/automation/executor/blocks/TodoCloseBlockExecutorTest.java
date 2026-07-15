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
class TodoCloseBlockExecutorTest {

    @Mock private TodoService todoService;

    @InjectMocks private TodoCloseBlockExecutor executor;

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
    }

    private BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("todo-close").config(config).build();
    }

    @Test
    void supports_todoCloseOnly() {
        assertThat(executor.supports("todo-close")).isTrue();
        assertThat(executor.supports("todo-update")).isFalse();
    }

    @Test
    void execute_setsStatusDone() {
        when(todoService.updateStatus(eq("todo-3"), any()))
                .thenReturn(Todo.builder().id("todo-3").title("Erledigt").build());

        Map<String, Object> config = new HashMap<>();
        config.put("id", "todo-3");

        StepResult result = executor.execute(block(config), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("todo-3");
        verify(todoService).updateStatus(eq("todo-3"), argThat(req -> req.status() == Todo.Status.DONE));
    }

    @Test
    void execute_idFromTemplateVariable_isResolved() {
        when(todoService.updateStatus(eq("todo-42"), any()))
                .thenReturn(Todo.builder().id("todo-42").title("T").build());

        ExecutionContext ctx = buildContext();
        ctx.setVariable("todo_id", "todo-42");
        Map<String, Object> config = new HashMap<>();
        config.put("id", "{{ todo_id }}");

        StepResult result = executor.execute(block(config), ctx);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(todoService).updateStatus(eq("todo-42"), any());
    }

    @Test
    void execute_missingId_failsWithoutServiceCall() {
        StepResult result = executor.execute(block(new HashMap<>()), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("id");
        verify(todoService, never()).updateStatus(anyString(), any());
    }
}
