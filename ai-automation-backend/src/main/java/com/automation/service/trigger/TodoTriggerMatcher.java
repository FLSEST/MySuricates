package com.automation.service.trigger;

import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.mapper.TodoMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ob ein TodoTrigger auf ein TODO passt – als reine Funktion, ohne Repository (TODO c6ea8f5c).
 *
 * <p>Dieselben Regeln entscheiden, ob {@link TodoTriggerService} einen Fix-Workflow startet, und ob
 * die Relationskarte eines Ziels den Trigger als Bearbeitung des TODOs zeigt. Deshalb arbeitet der
 * Abgleich auf zwei schmalen Records statt auf den Entities: Die Karte liest schlanke Projektionen
 * und soll dafuer keine Entity laden.
 *
 * <ul>
 *   <li>Tags: alle Tags des Musters (kommagetrennt) muessen am TODO stehen; leeres Muster passt immer.</li>
 *   <li>Projekt und Quelle: nur wenn gesetzt, dann genau gleich.</li>
 *   <li>Mindest-Prioritaet: das TODO muss mindestens so dringend sein; ein unbekannter Wert wird ignoriert.</li>
 * </ul>
 */
@Slf4j
public final class TodoTriggerMatcher {

    /** Die Filter eines Triggers. */
    public record Criteria(String tagPattern, String projectId, String sourceFilter, String minPriority) {
        public static Criteria of(TodoTrigger trigger) {
            return new Criteria(trigger.getTagPattern(), trigger.getProjectId(), trigger.getSourceFilter(), trigger.getMinPriority());
        }
    }

    /** Was der Abgleich vom TODO braucht; {@code tagsJson} wie in der Spalte {@code todos.tags}. */
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
        if (isSet(trigger.sourceFilter())
                && (todo.source() == null || !trigger.sourceFilter().equals(todo.source().name()))) {
            return false;
        }
        return matchesPriority(trigger.minPriority(), todo.priority());
    }

    private static boolean matchesPriority(String minPriority, Todo.Priority priority) {
        if (!isSet(minPriority)) {
            return true;
        }
        Todo.Priority minimum;
        try {
            minimum = Todo.Priority.valueOf(minPriority);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid minPriority '{}' on a TodoTrigger — ignored", minPriority);
            return true;
        }
        return priority != null && priority.ordinal() >= minimum.ordinal();
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
