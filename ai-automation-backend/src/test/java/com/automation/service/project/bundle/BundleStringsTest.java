package com.automation.service.project.bundle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundleStringsTest {

    @Test
    void copy_rewritesEveryStringValueButNoMapKey() {
        Map<String, Object> tree = Map.of("command", "echo hi",
                "nested", Map.of("items", List.of("a", "b")));

        Map<String, Object> copy = BundleStrings.copy(tree, "", (value, field) -> value.toUpperCase());

        assertThat(copy).containsOnlyKeys("command", "nested");
        assertThat(copy.get("command")).isEqualTo("ECHO HI");
        assertThat(copy.get("nested")).isEqualTo(Map.of("items", List.of("A", "B")));
    }

    @Test
    void copy_passesTheFieldPathOfEachValue() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("command", "x");
        tree.put("blocks", List.of(Map.of("config", Map.of("cmd", "y"))));
        List<String> fields = new ArrayList<>();

        BundleStrings.copy(tree, "", (value, field) -> {
            fields.add(field);
            return value;
        });
        BundleStrings.copy(List.of("z"), "blocks", (value, field) -> {
            fields.add(field);
            return value;
        });

        assertThat(fields).containsExactly("command", "blocks[0].config.cmd", "blocks[0]");
    }

    @Test
    void copy_keepsNonStringScalarsAndNulls() {
        Map<String, Object> tree = new HashMap<>();
        tree.put("count", 3);
        tree.put("flag", true);
        tree.put("missing", null);

        Map<String, Object> copy = BundleStrings.copy(tree, "", (value, field) -> "changed");

        assertThat(copy).containsEntry("count", 3).containsEntry("flag", true).containsEntry("missing", null);
    }

    @Test
    void copy_isAFreshTreeThatDoesNotShareMutableNodes() {
        List<Object> items = new ArrayList<>(List.of("a"));
        Map<String, Object> inner = new HashMap<>(Map.of("items", items));
        Map<String, Object> tree = new HashMap<>(Map.of("inner", inner));

        Map<String, Object> copy = BundleStrings.copy(tree, "", BundleStrings.IDENTITY);
        @SuppressWarnings("unchecked")
        Map<String, Object> copiedInner = (Map<String, Object>) copy.get("inner");
        @SuppressWarnings("unchecked")
        List<Object> copiedItems = (List<Object>) copiedInner.get("items");
        copiedItems.add("b");
        copiedInner.put("new", "x");

        assertThat(copy).isEqualTo(Map.of("inner", Map.of("items", List.of("a", "b"), "new", "x")));
        assertThat(items).containsExactly("a");
        assertThat(inner).containsOnlyKeys("items");
    }

    @Test
    void copy_turnsNonStringMapKeysIntoStrings() {
        Map<Object, Object> tree = new HashMap<>();
        tree.put(1, "one");

        Map<Object, Object> copy = BundleStrings.copy(tree, "", BundleStrings.IDENTITY);

        assertThat(copy).containsOnlyKeys("1");
    }

    @Test
    void copy_ofAPlainStringRewritesIt() {
        assertThat(BundleStrings.copy("abc", "instructions", (value, field) -> field + ":" + value))
                .isEqualTo("instructions:abc");
        assertThat(BundleStrings.<Object>copy(null, "", (value, field) -> "x")).isNull();
    }
}
