package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The placeholder-suspect string values of a block config, separated by level — the
 * level rule of the {@link DataFlowReferenceScanner} in a class of its own.
 *
 * <p>{@code direct} are the immediate values of the config map (the top level of the block),
 * {@code nested} everything below: switch cases, conditional branch lists, nested
 * block lists, but also value maps such as {@code integration.params}. Only on the top level
 * do position-based placeholders ({@code previous_output}, {@code step_N_output}) mean the
 * top-level block list.
 */
record DataFlowConfigStrings(List<String> direct, List<String> nested) {

    private static final int MAX_DEPTH = 20;

    /** Recursive over maps and lists; only strings with a curly brace are of interest. */
    static DataFlowConfigStrings of(Object config) {
        List<String> direct = new ArrayList<>();
        List<String> nested = new ArrayList<>();
        collect(config, direct, nested, 0);
        return new DataFlowConfigStrings(direct, nested);
    }

    private static void collect(Object value, List<String> direct, List<String> nested, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return;
        }
        if (value instanceof String s) {
            if (s.indexOf('{') >= 0) {
                (depth <= 1 ? direct : nested).add(s);
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, direct, nested, depth + 1));
        } else if (value instanceof Iterable<?> list) {
            list.forEach(v -> collect(v, direct, nested, depth + 1));
        }
    }
}
