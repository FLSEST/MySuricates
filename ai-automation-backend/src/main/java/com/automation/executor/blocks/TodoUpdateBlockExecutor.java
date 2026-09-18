package com.automation.executor.blocks;

import com.automation.dto.request.UpdateTodoRequest;
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
 * Native todo-update block (TODO 9f6d7f14): aktualisiert ein TODO direkt über
 * den TodoService — ersetzt REST-Self-Calls (PUT /api/v1/todos/{id}).
 *
 * Config: id (Pflicht, Template-fähig), optional title/description/priority/tags —
 * nur gesetzte Felder werden geändert (Null-Semantik von TodoService.update).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoUpdateBlockExecutor implements BlockExecutor {

    private final TodoService todoService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        StepResult result = TodoBlockSupport.startResult(context);
        try {
            String id = TodoBlockSupport.resolved(context, config.get("id"));
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("todo-update requires 'id' in config");
            }

            UpdateTodoRequest request = new UpdateTodoRequest(
                    TodoBlockSupport.resolved(context, config.get("title")),
                    TodoBlockSupport.resolved(context, config.get("description")),
                    null,
                    config.containsKey("priority")
                            ? TodoBlockSupport.parsePriority(config.get("priority"), null)
                            : null,
                    TodoBlockSupport.parseTags(config.get("tags")),
                    null, null, null, null, null);

            Todo updated = todoService.update(id, request);

            result.setInput("todo-update: " + id);
            result.setOutput("✓ TODO aktualisiert: " + updated.getId() + " — " + updated.getTitle());
            result.setStatus(StepStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("todo-update block failed: {}", e.getMessage());
            result.setOutput("todo-update fehlgeschlagen: " + e.getMessage());
            result.setStatus(StepStatus.FAILED);
        }
        return TodoBlockSupport.finishResult(result);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("todo-update");
    }
}
