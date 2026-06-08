package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Bulk status change for many TODOs at once — used by the cockpit's
 * "Verstaubtes archivieren" action and any future bulk operations.
 */
public record BulkUpdateTodoStatusRequest(
        @NotEmpty List<String> ids,
        @NotNull Todo.Status status
) {}
