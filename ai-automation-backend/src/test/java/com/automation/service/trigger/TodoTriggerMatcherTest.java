package com.automation.service.trigger;

import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.mapper.TodoMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Trigger-Abgleich als reine Funktion (TODO c6ea8f5c): Dieselben Regeln entscheiden, ob ein
 * TodoTrigger feuert ({@link TodoTriggerService}) und ob die Relationskarte eines Ziels ihn als
 * Bearbeitung eines TODOs zeigt. Die Faelle hier spiegeln die Matching-Tests des Services.
 */
class TodoTriggerMatcherTest {

    private static TodoTriggerMatcher.Criteria criteria(String tags, String project, String source, String minPriority) {
        return new TodoTriggerMatcher.Criteria(tags, project, source, minPriority);
    }

    private static TodoTriggerMatcher.Subject subject(List<String> tags, String project, Todo.Source source, Todo.Priority priority) {
        return new TodoTriggerMatcher.Subject(TodoMapper.listToJson(tags), project, source, priority);
    }

    @Test
    void tags_allRequired_andSemantics() {
        var todo = subject(List.of("backend", "testing", "auto:workflow"), null, Todo.Source.WORKFLOW, Todo.Priority.HIGH);
        assertThat(TodoTriggerMatcher.matches(criteria("backend, testing", null, null, null), todo)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("backend,security", null, null, null), todo)).isFalse();
    }

    @Test
    void emptyTagPattern_matchesAll_butTodoWithoutTags_missesAnyTagFilter() {
        var untagged = subject(List.of(), null, Todo.Source.USER, Todo.Priority.LOW);
        assertThat(TodoTriggerMatcher.matches(criteria("", null, null, null), untagged)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria(" , ", null, null, null), untagged)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("release", null, null, null), untagged)).isFalse();
    }

    @Test
    void projectFilter() {
        var todo = subject(List.of("backend"), "proj-1", Todo.Source.WORKFLOW, Todo.Priority.HIGH);
        assertThat(TodoTriggerMatcher.matches(criteria("backend", "proj-1", null, null), todo)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", "proj-2", null, null), todo)).isFalse();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", " ", null, null), todo)).isTrue();
    }

    @Test
    void sourceFilter_missingSourceNeverMatches() {
        var user = subject(List.of("backend"), null, Todo.Source.USER, Todo.Priority.HIGH);
        var noSource = subject(List.of("backend"), null, null, Todo.Priority.HIGH);
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, "WORKFLOW", null), user)).isFalse();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, "USER", null), user)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, "USER", null), noSource)).isFalse();
    }

    @Test
    void minPriority_atOrAbove_matches_invalidValueIsIgnored() {
        var medium = subject(List.of("backend"), null, Todo.Source.USER, Todo.Priority.MEDIUM);
        var critical = subject(List.of("backend"), null, Todo.Source.USER, Todo.Priority.CRITICAL);
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, null, "HIGH"), medium)).isFalse();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, null, "HIGH"), critical)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, null, "MEDIUM"), medium)).isTrue();
        assertThat(TodoTriggerMatcher.matches(criteria("backend", null, null, "URGENT"), medium)).isTrue();
    }

    @Test
    void entityAdapters_carryTheSameFields() {
        TodoTrigger trigger = TodoTrigger.builder().tagPattern("backend").projectId("p").sourceFilter("AI").minPriority("LOW").build();
        Todo todo = Todo.builder().tags(TodoMapper.listToJson(List.of("backend"))).projectId("p")
                .source(Todo.Source.AI).priority(Todo.Priority.LOW).build();
        assertThat(TodoTriggerMatcher.matches(TodoTriggerMatcher.Criteria.of(trigger), TodoTriggerMatcher.Subject.of(todo))).isTrue();
    }
}
