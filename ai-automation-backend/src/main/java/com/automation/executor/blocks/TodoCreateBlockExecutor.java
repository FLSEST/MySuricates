package com.automation.executor.blocks;

import com.automation.dto.request.CreateTodoRequest;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native todo-create block (TODO 9f6d7f14): creates a TODO directly via the
 * TodoService — replaces the REST self-calls (POST /api/v1/todos) from
 * shell blocks of the audit templates (anti-pattern: pool block risk).
 *
 * Config: title (required, template-capable), description, priority (default MEDIUM),
 * tags, project_id (default: context variable project_id), source (default WORKFLOW),
 * source_agent, variable_name (default todo_id — receives the new TODO id).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TodoCreateBlockExecutor implements BlockExecutor {

    private final TodoService todoService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        StepResult result = TodoBlockSupport.startResult(context);
        try {
            String title = TodoBlockSupport.resolved(context, config.get("title"));
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("todo-create requires 'title' in config");
            }
            String description = TodoBlockSupport.resolved(context, config.get("description"));
            // Template-capable like title: a branch can supply the priority (blueprint intake)
            Todo.Priority priority = TodoBlockSupport.parsePriority(
                    TodoBlockSupport.resolved(context, config.get("priority")), Todo.Priority.MEDIUM);
            List<String> tags = TodoBlockSupport.parseTags(config.get("tags"));
            String projectId = TodoBlockSupport.resolved(context, config.get("project_id"));
            if (projectId == null || projectId.isBlank()) {
                Object ctxProject = context.getVariable("project_id");
                projectId = ctxProject != null ? ctxProject.toString() : null;
            }
            Todo.Source source = TodoBlockSupport.parseSource(config.get("source"), Todo.Source.WORKFLOW);
            String sourceAgent = TodoBlockSupport.resolved(context, config.getOrDefault("source_agent", "workflow"));

            Todo created = todoService.create(new CreateTodoRequest(
                    title, description, Todo.Status.OPEN, priority, tags,
                    projectId, null, source, sourceAgent, null,
                    null,   // agentSessionRef: a workflow block has no agent conversation
                    context.getWorkflowRunId(), null, null, null));

            String variableName = String.valueOf(config.getOrDefault("variable_name", "todo_id"));
            context.setVariable(variableName, created.getId());

            result.setInput("todo-create: " + title);
            result.setOutput("✓ TODO angelegt: " + created.getId() + " — " + created.getTitle());
            result.setStatus(StepStatus.COMPLETED);
        } catch (Exception e) {
            log.warn("todo-create block failed: {}", e.getMessage());
            result.setOutput("todo-create fehlgeschlagen: " + e.getMessage());
            result.setStatus(StepStatus.FAILED);
        }
        return TodoBlockSupport.finishResult(result);
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("todo-create");
    }
}
