package com.automation.service.runoutput;

import com.automation.entity.BlockDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Says whether a workflow definition <b>orders</b> a gate verdict - i.e. whether any block
 * instructs its executor to write a {@code gate_status} marker.
 *
 * <p>The point: without this question a check workflow whose AI step fails silently cannot be told
 * from a workflow without a gate - both run through technically and become
 * GREEN (TODO 63ecc124). Gates stay voluntary; a verdict is only expected where the
 * definition itself asks for it. No new field, no migration, no follow-up maintenance of
 * existing workflows.
 *
 * <p>The same key that {@link GateStatusOutputParser} reads from the output is searched for
 * in all config values - also in nested blocks (foreach, switch, retry …), because the
 * instruction can be there just as well.
 */
public final class GateExpectation {

    private GateExpectation() {}

    /** As in the parser: {@code gate_status} and {@code gate-status}, case-insensitive. */
    private static final Pattern ORDERS_GATE = Pattern.compile("gate[_-]status", Pattern.CASE_INSENSITIVE);

    /** Schutz vor pathologisch tiefen Configs; echte Definitionen bleiben weit darunter. */
    private static final int MAX_DEPTH = 12;

    public static boolean isExpected(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return false;
        }
        return blocks.stream().anyMatch(block ->
                block != null && containsOrder(block.getConfig(), 0));
    }

    private static boolean containsOrder(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return false;
        }
        if (value instanceof String text) {
            return ORDERS_GATE.matcher(text).find();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(entry ->
                    containsOrder(entry.getKey(), depth + 1) || containsOrder(entry.getValue(), depth + 1));
        }
        if (value instanceof Collection<?> items) {
            return items.stream().anyMatch(item -> containsOrder(item, depth + 1));
        }
        return false;
    }
}
