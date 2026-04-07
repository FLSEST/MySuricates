package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

public record TodoResponse(
        String id,
        String title,
        String description,
        Todo.Status status,
        Todo.Priority priority,
        List<String> tags,
        String projectId,
        String projectName,
        Todo.Source source,
        String sourceAgent,
        String sessionId,
        String workflowRunId,
        String referenceType,
        String referenceId,
        int fixAttempts,
        String lastFixRunId,
        Instant dueDate,
        Instant createdAt,
        Instant updatedAt
) {}
