package com.automation.executor;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Walks the blocks nested inside a block's raw config, depth first: switch {@code cases[].blocks}
 * and {@code default}, plus a plain {@code blocks} list (foreach, retry, conditional).
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
     */
    public record Nested(String name, String type, Map<String, Object> config) {

        /** Name if it has one, else the type — the label validation messages use. */
        public String label() {
            return name != null && !name.isBlank() ? name : type;
        }
    }

    /** Visits every block nested in {@code config}, recursively, in definition order. */
    public static void visit(Map<String, Object> config, Consumer<Nested> visitor) {
        if (config == null) {
            return;
        }
        if (config.get("cases") instanceof List<?> cases) {
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry) {
                    visitList(caseEntry.get("blocks"), visitor);
                }
            }
        }
        visitList(config.get("default"), visitor);
        visitList(config.get("blocks"), visitor);
    }

    @SuppressWarnings("unchecked")
    private static void visitList(Object nestedBlocks, Consumer<Nested> visitor) {
        if (!(nestedBlocks instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> nested)) {
                continue;
            }
            Map<String, Object> nestedConfig = nested.get("config") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            visitor.accept(new Nested(
                    nested.get("name") instanceof String name ? name : null,
                    nested.get("type") instanceof String type ? type : null,
                    nestedConfig));
            visit(nestedConfig, visitor);
        }
    }
}
