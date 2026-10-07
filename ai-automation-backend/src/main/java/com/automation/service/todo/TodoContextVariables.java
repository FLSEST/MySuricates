package com.automation.service.todo;

import com.automation.entity.Todo;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code todo_*} context variables a workflow started for a todo receives — by the todo trigger
 * ({@code TodoTriggerService}) as well as by "resolve" ({@link TodoResolutionService}). One place,
 * so both runs see the same variables. Missing optional fields become an empty string
 * ({@code todo_tags}: {@code []}), never {@code null}.
 */
public final class TodoContextVariables {

    private TodoContextVariables() {}

    /** A new, mutable map with the todo's variables; callers may add their own keys. */
    public static Map<String, Object> of(Todo todo) {
        Map<String, Object> context = new HashMap<>();
        context.put("todo_id", todo.getId());
        context.put("todo_title", todo.getTitle());
        context.put("todo_description", todo.getDescription() != null ? todo.getDescription() : "");
        context.put("todo_tags", todo.getTags() != null ? todo.getTags() : "[]");
        context.put("todo_priority", todo.getPriority().name());
        context.put("todo_project_id", todo.getProjectId() != null ? todo.getProjectId() : "");
        context.put("todo_source", todo.getSource() != null ? todo.getSource().name() : "");
        context.put("todo_reference_type", todo.getReferenceType() != null ? todo.getReferenceType() : "");
        context.put("todo_reference_id", todo.getReferenceId() != null ? todo.getReferenceId() : "");
        return context;
    }
}
