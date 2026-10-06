package com.automation.service.trigger;

import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.mapper.TodoMapper;
import com.automation.util.EnumParameter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether a TodoTrigger matches a TODO - as a pure function, without a repository (TODO c6ea8f5c).
 *
 * <p>The same rules decide whether {@link TodoTriggerService} starts a fix workflow and whether
 * a goal's relation map shows the trigger as handling the TODO. That is why the
 * matching works on two slim records instead of the entities: the map reads slim projections
 * and should not load an entity for it.
 *
 * <ul>
 *   <li>Tags: all tags of the pattern (comma-separated) must be on the TODO; an empty pattern always matches.</li>
 *   <li>Project: only if set, then exactly equal. Source: only if set, then the same constant;
 *       an unknown source matches no TODO.</li>
 *   <li>Minimum priority: the TODO must be at least that urgent; an unknown value is ignored.</li>
 * </ul>
 */
@Slf4j
public final class TodoTriggerMatcher {

    /** The filters of a trigger. */
    public record Criteria(String tagPattern, String projectId, String sourceFilter, String minPriority) {
        public static Criteria of(TodoTrigger trigger) {
            return new Criteria(trigger.getTagPattern(), trigger.getProjectId(), trigger.getSourceFilter(), trigger.getMinPriority());
        }
    }

    /** What the matching needs from the TODO; {@code tagsJson} as in the column {@code todos.tags}. */
    public record Subject(String tagsJson, String projectId, Todo.Source source, Todo.Priority priority) {
        public static Subject of(Todo todo) {
            return new Subject(todo.getTags(), todo.getProjectId(), todo.getSource(), todo.getPriority());
        }
    }

    private TodoTriggerMatcher() {
    }

    public static boolean matches(Criteria trigger, Subject todo) {
        if (!matchesTags(trigger.tagPattern(), todo.tagsJson())) {
            return false;
        }
        if (isSet(trigger.projectId()) && !trigger.projectId().equals(todo.projectId())) {
            return false;
        }
        if (isSet(trigger.sourceFilter())) {
            // An unknown source matches no TODO - as the plain text comparison did before.
            Todo.Source source = sourceFilter(trigger.sourceFilter());
            if (source == null || todo.source() != source) {
                return false;
            }
        }
        return matchesPriority(trigger.minPriority(), todo.priority());
    }

    /**
     * The source of a trigger as the backlog query and {@link #matches} read it: trimmed, in
     * any spelling ({@link EnumParameter}, TODO a9bf13d5); {@code null} if not set
     * or unknown. New triggers store the constant's name, an unknown value can only
     * come from an older trigger.
     */
    public static Todo.Source sourceFilter(String value) {
        return storedFilter(Todo.Source.class, "sourceFilter", value);
    }

    private static boolean matchesPriority(String minPriority, Todo.Priority priority) {
        if (!isSet(minPriority)) {
            return true;
        }
        Todo.Priority minimum = storedFilter(Todo.Priority.class, "minPriority", minPriority);
        if (minimum == null) {
            return true;
        }
        return priority != null && priority.ordinal() >= minimum.ordinal();
    }

    private static <E extends Enum<E>> E storedFilter(Class<E> type, String field, String value) {
        if (!isSet(value)) {
            return null;
        }
        try {
            return EnumParameter.parse(type, field, value);
        } catch (IllegalArgumentException e) {
            log.warn("TodoTrigger with an unknown filter value: {}", e.getMessage());
            return null;
        }
    }

    private static boolean matchesTags(String tagPattern, String todoTagsJson) {
        if (!isSet(tagPattern)) {
            return true;
        }
        Set<String> required = Arrays.stream(tagPattern.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .collect(Collectors.toSet());
        if (required.isEmpty()) {
            return true;
        }
        List<String> todoTags = TodoMapper.jsonToList(todoTagsJson);
        return todoTags != null && new HashSet<>(todoTags).containsAll(required);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
