package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class NestedBlocksTest {

    private List<String> labelsOf(Map<String, Object> config) {
        List<String> seen = new ArrayList<>();
        NestedBlocks.visit(config, nested -> seen.add(nested.label()));
        return seen;
    }

    @Test
    void visitsCasesDefaultAndPlainBlocksInDefinitionOrder() {
        Map<String, Object> config = new HashMap<>(Map.of(
                "cases", List.of(
                        Map.of("value", "a", "blocks", List.of(Map.of("type", "shell", "name", "case-a"))),
                        Map.of("value", "b", "blocks", List.of(Map.of("type", "simple", "name", "case-b")))),
                "default", List.of(Map.of("type", "shell", "name", "fallback")),
                "blocks", List.of(Map.of("type", "simple", "name", "body"))));

        assertThat(labelsOf(config)).containsExactly("case-a", "case-b", "fallback", "body");
    }

    @Test
    void recursesDepthFirstIntoNestedContainers() {
        Map<String, Object> inner = Map.of("type", "switch", "name", "inner",
                "config", Map.of("blocks", List.of(Map.of("type", "shell", "name", "deep"))));
        Map<String, Object> config = new HashMap<>(Map.of(
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))),
                "blocks", List.of(Map.of("type", "shell", "name", "after"))));

        assertThat(labelsOf(config)).containsExactly("inner", "deep", "after");
    }

    /** Der Pfad nennt die Stelle unter dem äußeren Block — für Meldungen wie „Block 2 › cases[0] › Block 1“. */
    @Test
    void namesThePathBelowTheOuterBlockCountingBlocksFromOne() {
        Map<String, Object> inner = Map.of("type", "switch", "name", "inner",
                "config", Map.of("default", List.of(Map.of("type", "shell"), Map.of("type", "simple"))));
        Map<String, Object> config = new HashMap<>(Map.of(
                "cases", List.of(
                        Map.of("value", "a", "blocks", List.of(Map.of("type", "shell"))),
                        Map.of("value", "b", "blocks", List.of(Map.of("type", "shell"), inner)))));
        List<String> paths = new ArrayList<>();

        NestedBlocks.visit(config, nested -> paths.add(nested.path()));

        assertThat(paths).containsExactly(
                "cases[0] › Block 1",
                "cases[1] › Block 1",
                "cases[1] › Block 2",
                "cases[1] › Block 2 › default › Block 1",
                "cases[1] › Block 2 › default › Block 2");
    }

    @Test
    void fallsBackToTheTypeWhenTheNameIsMissingOrBlank() {
        Map<String, Object> config = new HashMap<>(Map.of("blocks", List.of(
                Map.of("type", "shell"),
                Map.of("type", "simple", "name", "  "))));

        assertThat(labelsOf(config)).containsExactly("shell", "simple");
    }

    @Test
    void reportsEntriesWithoutTypeOrConfigInsteadOfSkippingThem() {
        List<NestedBlocks.Nested> seen = new ArrayList<>();
        Map<String, Object> config = new HashMap<>(Map.of("blocks", List.of(
                Map.of("name", "typeless"),
                Map.of("type", "shell", "config", Map.of("command", "ls")))));

        NestedBlocks.visit(config, seen::add);

        assertThat(seen).hasSize(2);
        assertThat(seen.getFirst().type()).isNull();
        assertThat(seen.getFirst().config()).isNull();
        assertThat(seen.get(1).config()).containsEntry("command", "ls");
    }

    @Test
    void nonListAndNonMapEntriesAreIgnoredWithoutThrowing() {
        Map<String, Object> config = new HashMap<>();
        config.put("cases", "not-a-list");
        config.put("default", 42);
        List<Object> mixed = new ArrayList<>();
        mixed.add("plain string");
        mixed.add(null);
        mixed.add(Map.of("type", "shell", "name", "ok"));
        config.put("blocks", mixed);

        assertThat(labelsOf(config)).containsExactly("ok");
        assertThatCode(() -> NestedBlocks.visit(null, nested -> {
        })).doesNotThrowAnyException();
    }

    @Test
    void caseEntriesWithoutABlocksListAreIgnored() {
        Map<String, Object> config = new HashMap<>(Map.of(
                "cases", List.of(Map.of("value", "a"), "not-a-map")));

        assertThat(labelsOf(config)).isEmpty();
    }

    @Test
    void nestedCarriesEveryKeyOfItsMap_soStrayFieldsBesideConfigAreVisible() {
        Map<String, Object> stray = new java.util.LinkedHashMap<>();
        stray.put("type", "shell");
        stray.put("config", Map.of("command", "ls"));
        stray.put("on_failure", "continue");
        List<java.util.Set<String>> fields = new ArrayList<>();

        NestedBlocks.visit(Map.of("default", List.of(stray)), nested -> fields.add(nested.fields()));

        assertThat(fields).containsExactly(java.util.Set.of("type", "config", "on_failure"));
    }
}
