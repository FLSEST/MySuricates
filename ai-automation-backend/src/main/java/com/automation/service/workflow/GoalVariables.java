package com.automation.service.workflow;

import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code goal_status} and {@code goal_guard_*} (TODO 259f4f66) — only for a run whose merged context
 * carries {@code goal_id} (the goal guard sets it as default context and schedule input). An unknown
 * goal throws in {@link GoalContextSource}; the collector then states the gap in every one of them.
 * Moved out of {@link WorkflowContextDataService} (TODO 441a4c46).
 */
@Component
@Order(40)
@RequiredArgsConstructor
class GoalVariables implements PlatformVariableSource {

    private static final Pattern UNSAFE_ID_CHARS = Pattern.compile("[^A-Za-z0-9\\-]");

    private final GoalContextSource goalContextSource;

    @Override
    public List<String> allNames() {
        return GoalContextSource.VARIABLES;
    }

    @Override
    public List<String> names(Request request) {
        return goalId(request) != null ? allNames() : List.of();
    }

    @Override
    public Map<String, String> load(Request request) {
        Object locale = request.variable("goal_locale");
        return goalContextSource.variables(goalId(request), locale == null ? null : locale.toString());
    }

    /** The goal id reduced to id characters, {@code null} when the run has none. */
    private static String goalId(Request request) {
        Object raw = request.variable("goal_id");
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        return UNSAFE_ID_CHARS.matcher(raw.toString()).replaceAll("");
    }
}
