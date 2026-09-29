package com.automation.service.integration.providers.todoist;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoistParamsTest {

    @Test
    void extractToken_takesPartAfterFirstPipe() {
        assertThat(TodoistParams.extractToken("label|tok")).isEqualTo("tok");
        assertThat(TodoistParams.extractToken("label|a|b")).isEqualTo("a|b");
        assertThat(TodoistParams.extractToken("plain")).isEqualTo("plain");
        assertThat(TodoistParams.extractToken(null)).isNull();
    }

    @Test
    void requireParam_missingOrBlankFails() {
        assertThat(TodoistParams.requireParam(Map.of("k", 5), "k")).isEqualTo("5");
        assertThatThrownBy(() -> TodoistParams.requireParam(Map.of(), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> TodoistParams.requireParam(Map.of("k", "  "), "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionalString_blankIsNull() {
        assertThat(TodoistParams.optionalString(Map.of("k", "v"), "k")).isEqualTo("v");
        assertThat(TodoistParams.optionalString(Map.of("k", " "), "k")).isNull();
        assertThat(TodoistParams.optionalString(Map.of(), "k")).isNull();
    }

    @Test
    void id_acceptsOpaqueIdsAndRejectsPathTricks() {
        assertThat(TodoistParams.id("6XGgmFVcrG5RRjVr", "task_id")).isEqualTo("6XGgmFVcrG5RRjVr");
        for (String bad : new String[]{"../x", "a/b", "a b", "", "a".repeat(65)}) {
            assertThatThrownBy(() -> TodoistParams.id(bad, "task_id"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("task_id");
        }
        assertThatThrownBy(() -> TodoistParams.id(null, "task_id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TodoistParams.requireId(Map.of("task_id", "a/b"), "task_id"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TodoistParams.requireId(Map.of("task_id", "abc"), "task_id")).isEqualTo("abc");
    }

    @Test
    void limit_defaultsTo50_readsTextAndChecksRange() {
        assertThat(TodoistParams.limit(Map.of())).isEqualTo(50);
        assertThat(TodoistParams.limit(Map.of("limit", " 7 "))).isEqualTo(7);
        assertThat(TodoistParams.limit(Map.of("limit", 200))).isEqualTo(200);
        assertThatThrownBy(() -> TodoistParams.limit(Map.of("limit", 0))).hasMessageContaining("between 1 and 200");
        assertThatThrownBy(() -> TodoistParams.limit(Map.of("limit", 201))).hasMessageContaining("between 1 and 200");
        assertThatThrownBy(() -> TodoistParams.limit(Map.of("limit", 1.5))).hasMessageContaining("'limit'");
    }

    @Test
    void priority_nullOrOneToFour() {
        assertThat(TodoistParams.priority(new HashMap<>())).isNull();
        assertThat(TodoistParams.priority(Map.of("priority", "3"))).isEqualTo(3);
        assertThatThrownBy(() -> TodoistParams.priority(Map.of("priority", 5))).hasMessageContaining("between 1");
        assertThatThrownBy(() -> TodoistParams.priority(Map.of("priority", 0))).hasMessageContaining("between 1");
        assertThatThrownBy(() -> TodoistParams.priority(Map.of("priority", "high"))).hasMessageContaining("number");
        assertThatThrownBy(() -> TodoistParams.priority(Map.of("priority", 2.5))).hasMessageContaining("number");
    }

    @Test
    void labels_acceptsCollectionOrCommaSeparatedText() {
        assertThat(TodoistParams.labels(null)).isEmpty();
        assertThat(TodoistParams.labels(" a, b ,, c")).containsExactly("a", "b", "c");
        assertThat(TodoistParams.labels(List.of("x", " y ", ""))).containsExactly("x", "y");
    }

    @Test
    void urlEncode_encodesReservedCharacters() {
        assertThat(TodoistParams.urlEncode("a b&c/ü")).isEqualTo("a+b%26c%2F%C3%BC");
    }
}
