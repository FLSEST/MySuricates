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
        String referenceType,
        String referenceId
) {}
