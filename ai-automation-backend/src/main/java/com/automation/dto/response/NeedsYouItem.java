package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;

/**
 * A single cross-project item that needs the user's attention
 * (USER-source, CRITICAL, or overdue), enriched with project name and age.
 */
public record NeedsYouItem(
        String id,
        String title,
        String projectId,
        String projectName,
        Todo.Priority priority,
        Instant dueDate,
        Todo.Source source,
        Instant createdAt,
        Instant updatedAt,
        long ageDays,
        boolean overdue
) {}
