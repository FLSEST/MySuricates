package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTodoRequest(
        @NotBlank String title,
        String description,
        Todo.Status status,
        Todo.Priority priority,
        List<String> tags,
        String projectId,
        String linkedWorkflowId,
        Todo.Source source,
        String sourceAgent,
        String sessionId,
        String workflowRunId,
        String referenceType,
        String referenceId,
        String dueDate
) {}
