package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.ExecutionMode;
import com.automation.executor.NestedBlocks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
        if (SHELL_SWITCHABLE.contains(block.getType()) && runsAsShell(block.getConfig())) return List.of();
        return promptTexts(block.getType(), block.getConfig());
    }

    /**
     * Die Auftragstexte der verschachtelten Blöcke je Blockname (ohne Namen: Typ), in
     * Definitionsreihenfolge. Gelaufen wird über denselben Walker wie die Typ- und
     * {@code execution_mode}-Prüfung ({@link NestedBlocks}: switch-Cases, {@code default}, eine
     * {@code blocks}-Liste). Ein simple-Block im switch-Case bekommt {@code workflow.instructions}
     * genauso als Systemprompt, mit {@code execution_mode: "shell"} läuft er als Shell
     * ({@code SwitchBlockExecutor}); gelesen wurden bis TODO ce4abac1 nur die direkten Werte.
     */
    static Map<String, List<String>> nestedInstructions(BlockDefinition block) {
        Map<String, List<String>> texts = new LinkedHashMap<>();
        if (block == null) return texts;
        NestedBlocks.visit(block.getConfig(), nested -> {
            if (nested.type() == null || nested.config() == null || runsAsShell(nested.config())) return;
            List<String> prompts = promptTexts(nested.type(), nested.config());
            if (!prompts.isEmpty()) texts.computeIfAbsent(nested.label(), label -> new ArrayList<>()).addAll(prompts);
        });
        return texts;
    }

    private static boolean runsAsShell(Map<String, Object> config) {
        return ExecutionMode.SHELL.equals(config.get(ExecutionMode.KEY));
    }

    private static List<String> promptTexts(String type, Map<String, Object> config) {
        List<String> texts = new ArrayList<>();
        for (String key : PROMPT_KEYS.getOrDefault(type, List.of())) {
            if (config.get(key) instanceof String text) texts.add(text);
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
