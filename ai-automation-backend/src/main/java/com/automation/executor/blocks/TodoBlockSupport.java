package com.automation.executor.blocks;

import com.automation.entity.StepResult;
import com.automation.entity.Todo;
import com.automation.executor.ExecutionContext;

import java.time.Instant;
import java.util.List;

/** Shared config parsing/result plumbing for the native todo-* blocks. */
final class TodoBlockSupport {

    private TodoBlockSupport() {}

    static StepResult startResult(ExecutionContext context) {
        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());
        return result;
    }

    static StepResult finishResult(StepResult result) {
        result.setCompletedAt(Instant.now());
        result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
        return result;
    }

    /** Resolves {{ variable }} templates in string config values; null-safe. */
    static String resolved(ExecutionContext context, Object value) {
        return value == null ? null : context.resolveTemplate(value.toString());
    }

    static Todo.Priority parsePriority(Object value, Todo.Priority fallback) {
        if (value == null) return fallback;
        try {
            return Todo.Priority.valueOf(value.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static Todo.Source parseSource(Object value, Todo.Source fallback) {
        if (value == null) return fallback;
        try {
            return Todo.Source.valueOf(value.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Tags may arrive as list or newline/comma separated string; null when absent. */
    static List<String> parseTags(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        return List.of(s.split("\\s*,\\s*"));
    }
}
