package com.automation.executor.blocks;

import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.todo.TodoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Native todo-close block (TODO 9f6d7f14): sets a TODO to DONE - directly via
 * the TodoService, replaces REST self-calls (PATCH /api/v1/todos/{id}/status).
 *
 * Config: id (required, template-capable - typically {{ todo_id }} from todo-create).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoCloseBlockExecutor implements BlockExecutor {

    private final TodoService todoService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        StepResult result = TodoBlockSupport.startResult(context);
        try {
            String id = TodoBlockSupport.resolved(context, config.get("id"));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("todo-close requires 'id' in config");
            }

            Todo closed = todoService.updateStatus(id, new UpdateTodoStatusRequest(Todo.Status.DONE, null));

            result.setInput("todo-close: " + id);
            result.setOutput("✓ TODO geschlossen: " + closed.getId() + " — " + closed.getTitle());
            result.setStatus(StepStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("todo-close block failed: {}", e.getMessage());
            result.setOutput("todo-close fehlgeschlagen: " + e.getMessage());
            result.setStatus(StepStatus.FAILED);
        }
        return TodoBlockSupport.finishResult(result);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("todo-close");
    }
}
