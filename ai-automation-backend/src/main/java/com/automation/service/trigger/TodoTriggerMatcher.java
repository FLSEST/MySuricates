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
 * Ob ein TodoTrigger auf ein TODO passt – als reine Funktion, ohne Repository (TODO c6ea8f5c).
 *
 * <p>Dieselben Regeln entscheiden, ob {@link TodoTriggerService} einen Fix-Workflow startet, und ob
 * die Relationskarte eines Ziels den Trigger als Bearbeitung des TODOs zeigt. Deshalb arbeitet der
 * Abgleich auf zwei schmalen Records statt auf den Entities: Die Karte liest schlanke Projektionen
 * und soll dafuer keine Entity laden.
 *
 * <ul>
 *   <li>Tags: alle Tags des Musters (kommagetrennt) muessen am TODO stehen; leeres Muster passt immer.</li>
 *   <li>Projekt: nur wenn gesetzt, dann genau gleich. Quelle: nur wenn gesetzt, dann dieselbe Konstante;
 *       eine unbekannte Quelle passt auf kein TODO.</li>
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
        if (isSet(trigger.sourceFilter())) {
            // Eine unbekannte Quelle passt auf kein TODO – wie vorher der reine Textvergleich.
            Todo.Source source = sourceFilter(trigger.sourceFilter());
            if (source == null || todo.source() != source) {
                return false;
            }
        }
        return matchesPriority(trigger.minPriority(), todo.priority());
    }

    /**
     * Die Quelle eines Triggers, wie Backlog-Abfrage und {@link #matches} sie lesen: getrimmt, in
     * beliebiger Schreibweise ({@link EnumParameter}, TODO a9bf13d5); {@code null}, wenn nicht gesetzt
     * oder unbekannt. Neue Trigger speichern den Namen der Konstante, ein unbekannter Wert kann nur
     * aus einem aelteren Trigger stammen.
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
