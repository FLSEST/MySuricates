package com.automation.executor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Walks the blocks nested inside a block's raw config, depth first: switch {@code cases[].blocks}
 * and {@code default} — the only nested blocks any executor runs — plus a plain {@code config.blocks}
 * list. <b>No executor runs {@code config.blocks}</b>, on no block type: foreach, retry, conditional and
 * while run their {@code command} (or {@code then_command}/{@code else_command}), never a block list
 * (5536c5aa). The list is walked anyway, so that every validation sees what the definition holds; the
 * analyzer reports it as {@code dead-nested-blocks}.
 *
 * <p>One walker for every validation of the same tree. {@code BlockTypeValidator} and
 * {@link ExecutionMode} used to traverse it separately and had already drifted apart — the type
 * check saw {@code config.blocks}, the {@code execution_mode} check did not (TODO d15fb3a3).
 *
 * <p>Nested blocks are raw maps, not {@code BlockDefinition}s: they are only deserialized when the
 * enclosing block runs. An entry without a {@code config} map is still visited — a block with no
 * type at all is exactly what the type check must reject.
 */
public final class NestedBlocks {

    private NestedBlocks() {
    }

    /**
     * One nested block as it sits in the definition JSON.
     *
     * @param name   its {@code name}, or null
     * @param type   its {@code type}, or null when missing or not a string
     * @param config its {@code config} map, or null when missing or not a map
     * @param path   where it sits below the enclosing top-level block, e.g. {@code "cases[0] › Block 1"}
     *               or {@code "default › Block 2 › blocks › Block 1"}; block numbers count from 1
     * @param fields every key of the nested block's map — besides {@code name}, {@code type} and
     *               {@code config} whatever else stands there, which a run never reads
     */
    public record Nested(String name, String type, Map<String, Object> config, String path, Set<String> fields) {

        /** Name if it has one, else the type — the label validation messages use. */
        public String label() {
            return name != null && !name.isBlank() ? name : type;
        }
    }

    /** Visits every block nested in {@code config}, recursively, in definition order. */
    public static void visit(Map<String, Object> config, Consumer<Nested> visitor) {
        visit(config, "", visitor);
    }

    private static void visit(Map<String, Object> config, String prefix, Consumer<Nested> visitor) {
        if (config == null) {
            return;
        }
        if (config.get("cases") instanceof List<?> cases) {
            for (int i = 0; i < cases.size(); i++) {
                if (cases.get(i) instanceof Map<?, ?> caseEntry) {
                    visitList(caseEntry.get("blocks"), prefix + "cases[" + i + "]", visitor);
                }
            }
        }
        visitList(config.get("default"), prefix + "default", visitor);
        visitList(config.get("blocks"), prefix + "blocks", visitor);
    }

    @SuppressWarnings("unchecked")
    private static void visitList(Object nestedBlocks, String listPath, Consumer<Nested> visitor) {
        if (!(nestedBlocks instanceof List<?> list)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> nested)) {
                continue;
            }
            Map<String, Object> nestedConfig = nested.get("config") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            String path = listPath + " › Block " + (i + 1);
            visitor.accept(new Nested(
                    nested.get("name") instanceof String name ? name : null,
                    nested.get("type") instanceof String type ? type : null,
                    nestedConfig,
                    path,
                    nested.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new))));
            visit(nestedConfig, path + " › ", visitor);
        }
    }
}
