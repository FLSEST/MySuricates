package com.automation.dto.projection;

import com.automation.entity.Todo;

import java.time.Instant;

/**
 * Lightweight projection for the cross-project "Braucht dich" lane.
 * Selects only short scalar columns — never the description blob — so the
 * overview query stays lean under pool-size=1.
 */
public record NeedsYouRow(
        String id,
        String title,
        String projectId,
        Todo.Priority priority,
        Instant dueDate,
        Todo.Source source,
        Instant createdAt,
        Instant updatedAt
) {}
