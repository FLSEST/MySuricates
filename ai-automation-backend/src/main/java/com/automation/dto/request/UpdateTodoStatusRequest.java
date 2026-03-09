package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.constraints.NotNull;

public record UpdateTodoStatusRequest(
        @NotNull Todo.Status status,
        String sessionId
) {}
