package com.automation.dto.projection;

import com.automation.entity.Todo;

/** Title and priority of a TODO – never the description blob, for prompt context lists. */
public record TodoHeadline(
        String title,
        Todo.Priority priority
) {}
