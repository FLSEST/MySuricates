package com.automation.service.assistant;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.entity.Todo;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Confirmed assistant tools that touch todos ({@code create_todo}, {@code update_todo_status}).
 * Each method takes the sanitized JSON payload and returns the user-facing result line
 * ({@code ✓ …} on success, {@code ⚠ …} otherwise) — never throws.
 *
 * <p>Extracted from {@code AssistantToolExecutor} (Audit-TODO 9f166f55). Guardrails, audit and
 * dispatch stay in the executor.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantTodoTools {

    private final TodoService todoService;
    private final ObjectMapper objectMapper;

    public String createTodo(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String title = (String) params.get("title");
            if (title == null || title.isBlank()) return "⚠ Could not create todo: title is missing.";

            String description = (String) params.getOrDefault("description", null);
            String priorityStr = (String) params.getOrDefault("priority", "MEDIUM");
            Object tagsObj = params.get("tags");
            String projectId = (String) params.getOrDefault("projectId", null);

            Todo.Priority priority;
            try { priority = Todo.Priority.valueOf(priorityStr.toUpperCase()); }
            catch (Exception e) { priority = Todo.Priority.MEDIUM; }

            List<String> tags = tagsObj instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();

            var request = new CreateTodoRequest(
                    title, description, Todo.Status.OPEN, priority, tags,
                    projectId, null, Todo.Source.AI, "assistant", null, null, null, null, null
            );
            Todo created = todoService.create(request);
            String tagStr = tags.isEmpty() ? "" : ", Tags: " + String.join(", ", tags);
            String shortId = created.getId().substring(0, Math.min(8, created.getId().length()));
            return "✓ Todo created: \"" + created.getTitle() + "\" (priority: " + priority + tagStr + ", ID: " + shortId + "…)";
        } catch (Exception e) {
            log.warn("Assistant create_todo tool failed. Payload: {}", json, e);
            return "⚠ Could not create todo: " + e.getMessage();
        }
    }

    public String updateTodoStatus(String json) {
        try {
            Map<String, Object> params = objectMapper.readValue(json, new TypeReference<>() {});
            String id = (String) params.get("id");
            String statusStr = (String) params.get("status");
            if (id == null || statusStr == null) return "⚠ update_todo_status: id and status are required fields.";

            Todo.Status status;
            try { status = Todo.Status.valueOf(statusStr.toUpperCase()); }
            catch (Exception e) { return "⚠ Invalid status: " + statusStr + ". Allowed: OPEN, IN_PROGRESS, DONE, BACKLOG"; }

            todoService.updateStatus(id, new UpdateTodoStatusRequest(status, null));
            return "✓ Todo status updated: " + status + " (ID: " + id.substring(0, Math.min(8, id.length())) + "…)";
        } catch (Exception e) {
            log.warn("Assistant update_todo_status tool failed. Payload: {}", json, e);
            return "⚠ Could not update status: " + e.getMessage();
        }
    }
}
