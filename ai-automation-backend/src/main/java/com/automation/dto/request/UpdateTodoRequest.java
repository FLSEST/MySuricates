package com.automation.dto.request;

import com.automation.entity.Todo;

import java.util.List;

public record UpdateTodoRequest(
        String title,
        String description,
        Todo.Status status,
        Todo.Priority priority,
        List<String> tags,
        String projectId,
        String linkedWorkflowId,
        String referenceType,
        String referenceId,
        /** UUID hinter `claude --resume`; null laesst den vorhandenen Wert stehen. */
        String agentSessionRef,
        String dueDate
) {
    /** Kurzform ohne {@code agentSessionRef} (TODO 3497533c) — siehe CreateTodoRequest. */
    public UpdateTodoRequest(String title, String description, Todo.Status status,
                             Todo.Priority priority, List<String> tags, String projectId,
                             String linkedWorkflowId, String referenceType, String referenceId,
                             String dueDate) {
        this(title, description, status, priority, tags, projectId, linkedWorkflowId,
                referenceType, referenceId, null, dueDate);
    }
}
