package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.ExecutionMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Wo ein Block seinen Auftrag an die KI führt: je Typ die Schlüssel, die sein Executor liest
     * (dieselben wie in {@code BlockSpecs}). Nur Aufrufe, die {@code workflow.instructions} als
     * Systemprompt mitbekommen, also nicht die Bedingung von conditional und nicht compress-ai.
     * Gelesen wurde früher {@code prompt} vor {@code command} und {@code goal} nie (TODO c52eba8f).
     */
    static final Map<String, List<String>> PROMPT_KEYS = Map.of(
            "simple", List.of("command"),
            "agentic", List.of("goal"),
            "skill-capability", List.of("input"),
            "retry", List.of("command"),
            "foreach", List.of("command"),
            "while", List.of("command"),
            "conditional", List.of("then_command", "else_command"));

    /** Typen, deren Befehl mit {@code execution_mode: "shell"} als Shell läuft statt als KI-Auftrag. */
    private static final Set<String> SHELL_SWITCHABLE = Set.of("retry", "foreach", "while", "conditional");

    /**
     * Die Auftragstexte, die ein Block zusammen mit {@code workflow.instructions} an die KI schickt,
     * in der Reihenfolge von {@link #PROMPT_KEYS}. Leer für Shell-Befehle, Fragen an Menschen
     * (approval, human-input) und Blöcke ohne KI-Aufruf.
     */
    static List<String> instructions(BlockDefinition block) {
        if (block == null || block.getType() == null || block.getConfig() == null) return List.of();
        if (SHELL_SWITCHABLE.contains(block.getType())
                && ExecutionMode.SHELL.equals(block.getConfig().get(ExecutionMode.KEY))) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (String key : PROMPT_KEYS.getOrDefault(block.getType(), List.of())) {
            String text = directValue(block, key);
            if (text != null) texts.add(text);
        }
        return texts;
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
