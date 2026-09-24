package com.automation.service.runoutput;

import com.automation.entity.BlockDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sagt, ob eine Workflow-Definition ein Gate-Urteil <b>bestellt</b> — ob also irgendein Block
 * seinen Ausführenden anweist, einen {@code gate_status}-Marker zu schreiben.
 *
 * <p>Der Sinn: Ohne diese Frage ist ein Prüf-Workflow, dessen KI-Schritt still fehlschlägt, von
 * einem Workflow ohne Gate nicht zu unterscheiden — beide laufen technisch durch und werden
 * GREEN (TODO 63ecc124). Gates bleiben freiwillig; erwartet wird ein Urteil nur dort, wo die
 * Definition selbst danach fragt. Kein neues Feld, keine Migration, keine Nachpflege an
 * bestehenden Workflows.
 *
 * <p>Gesucht wird derselbe Schlüssel, den {@link GateStatusOutputParser} aus der Ausgabe liest,
 * in allen Config-Werten — auch in verschachtelten Blöcken (foreach, switch, retry …), weil die
 * Anweisung dort genauso stehen kann.
 */
public final class GateExpectation {

    private GateExpectation() {}

    /** Wie im Parser: {@code gate_status} und {@code gate-status}, Groß-/Kleinschreibung egal. */
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
