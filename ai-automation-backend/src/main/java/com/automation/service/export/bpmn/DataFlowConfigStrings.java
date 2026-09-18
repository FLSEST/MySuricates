package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Die platzhalterverdächtigen String-Werte einer Block-Config, getrennt nach Ebene — die
 * Ebenen-Regel des {@link DataFlowReferenceScanner} in einer eigenen Klasse.
 *
 * <p>{@code direct} sind die unmittelbaren Werte der Config-Map (die oberste Ebene des Blocks),
 * {@code nested} alles darunter: Switch-Cases, Conditional-Zweiglisten, verschachtelte
 * Blocklisten, aber auch Wertemaps wie {@code integration.params}. Nur auf der obersten Ebene
 * meinen positionsbezogene Platzhalter ({@code previous_output}, {@code step_N_output}) die
 * Top-Level-Blockliste.
 */
record DataFlowConfigStrings(List<String> direct, List<String> nested) {

    private static final int MAX_DEPTH = 20;

    /** Rekursiv über Maps und Listen; nur Strings mit einer geschweiften Klammer sind interessant. */
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
