package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Liest die Texte einer Block-Konfiguration — flach und rekursiv.
 *
 * <p>Die Analyse muss verschachtelte Blöcke (foreach, switch, retry, while, conditional) mitsehen:
 * Ein absoluter Pfad oder ein nie belegter Platzhalter ist dort genauso ein Fehler wie oben.
 * Gleichzeitig braucht ein Teil der Regeln nur die direkten Werte eines Blocks — deshalb beides.
 */
final class BlockText {

    private BlockText() {}

    /** Schutz vor pathologisch tiefen Configs; echte Definitionen bleiben weit darunter. */
    private static final int MAX_DEPTH = 12;

    /** Alle Zeichenketten der Config, verschachtelte eingeschlossen. */
    static List<String> allStrings(BlockDefinition block) {
        List<String> out = new ArrayList<>();
        collect(block == null ? null : block.getConfig(), out, 0);
        return out;
    }

    /** Der zusammengefasste Text eines Blocks — für Suchen, bei denen die Struktur egal ist. */
    static String joined(BlockDefinition block) {
        return String.join("\n", allStrings(block));
    }

    /** Der Wert eines direkten Config-Schlüssels als Text, oder {@code null}. */
    static String directValue(BlockDefinition block, String key) {
        if (block == null || block.getConfig() == null) return null;
        Object value = block.getConfig().get(key);
        return value instanceof String text ? text : null;
    }

    /**
     * Der Auftragstext eines Blocks: {@code prompt} bei KI-Blöcken, {@code command} bei Shell- und
     * (historisch gewachsen) auch bei simple-Blöcken, die ihren Prompt dort führen.
     */
    static String instruction(BlockDefinition block) {
        String prompt = directValue(block, "prompt");
        return prompt != null ? prompt : directValue(block, "command");
    }

    private static void collect(Object value, List<String> out, int depth) {
        if (value == null || depth > MAX_DEPTH) return;
        if (value instanceof String text) {
            out.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, out, depth + 1));
        } else if (value instanceof Collection<?> items) {
            items.forEach(item -> collect(item, out, depth + 1));
        }
    }
}
