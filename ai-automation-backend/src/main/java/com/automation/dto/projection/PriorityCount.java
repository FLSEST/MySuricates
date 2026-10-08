package com.automation.dto.projection;

import com.automation.entity.Todo;

/** Number of TODOs with one priority – a GROUP BY row, no entity loaded. */
public record PriorityCount(
        Todo.Priority priority,
        long cnt
) {}
