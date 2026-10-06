package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regeln zur <b>Hygiene</b> einzelner Blöcke: Fehlerabbruch im Shell-Block, Pfade mit Klarnamen,
 * doppelte Anweisungen, datierte Modell-IDs.
 *
 * <p>Alle deterministisch am Text prüfbar — kein KI-Aufruf, keine Heuristik mit Schwellwert.
 * Was ein Urteil bräuchte (redundante KI-Aufrufe, Sparsamkeits-Leiter, zu knappe Timeouts),
 * steht bewusst nicht hier; siehe Klassendoku von {@link WorkflowAnalyzer}.
 */
final class HygieneRules {

    private HygieneRules() {}

    /** Ein Pfad unter einem Benutzerverzeichnis — trägt den Klarnamen und bricht auf jedem anderen Rechner. */
    private static final Pattern HOME_PATH = Pattern.compile("(?:/home|/Users)/([A-Za-z0-9._-]+)/");

    /** Wie {@code TemplateHygieneGateTest}: Aliase statt datierter IDs, die still veralten. */
    private static final Pattern DATED_MODEL = Pattern.compile("claude-(opus|sonnet|haiku|fable)-\\d");

    static void apply(List<BlockDefinition> blocks, String workflowInstructions, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            String name = block.getName();

            shellRules(block, i, name, hostPathText(block), out);
            duplicatedInstructions(block, workflowInstructions, i, name, out);
            datedModel(block, i, name, out);
        }
    }

    /**
     * The block's text for the home-path check. A {@code mounts} entry ({@code host:container[:mode]})
     * counts with its host side only: the container side is a path inside the image, such as the
     * image user's home, and the same on every machine (cccb0301).
     */
    private static String hostPathText(BlockDefinition block) {
        List<String> texts = new ArrayList<>(BlockText.allStrings(block));
        if (block.getConfig() != null && block.getConfig().get("mounts") instanceof List<?> mounts) {
            for (Object mount : mounts) {
                if (mount instanceof String entry && texts.remove(entry)) {
                    int colon = entry.indexOf(':');
                    texts.add(colon < 0 ? entry : entry.substring(0, colon));
                }
            }
        }
        return String.join("\n", texts);
    }

    private static void shellRules(BlockDefinition block, int i, String name, String all,
                                   List<AnalyzerFinding> out) {
        var matcher = HOME_PATH.matcher(all);
        if (matcher.find()) {
            out.add(AnalyzerFinding.of("absolute-home-path", Severity.HIGH, i, name,
                    "enthält einen Pfad unter /home/" + matcher.group(1)
                            + " — trägt den Klarnamen und bricht auf jedem anderen Rechner",
                    "contains a path under /home/" + matcher.group(1)
                            + " — carries the user's real name and breaks on every other machine"));
        }
        if (!"shell".equals(block.getType())) return;
        String command = BlockText.directValue(block, "command");
        if (command != null && !command.isBlank() && !command.contains("set -e") && !command.contains("set -o errexit")) {
            out.add(AnalyzerFinding.of("missing-strict-mode", Severity.LOW, i, name,
                    "Shell-Block ohne 'set -euo pipefail' — ein Fehler in der Mitte bleibt unbemerkt, "
                            + "der Block endet trotzdem mit 0",
                    "shell block without 'set -euo pipefail' — an error halfway through goes unnoticed, "
                            + "the block still exits with 0"));
        }
    }

    /**
     * Der KI-Auftrag des Blocks ({@link BlockText#instructions}) oder eines in ihm verschachtelten
     * Blocks ({@link BlockText#nestedInstructions}, TODO ce4abac1) wiederholt wörtlich, was ohnehin
     * über {@code workflow.instructions} in jeden Aufruf geht: dieselben Regeln zweimal im Kontext,
     * doppelt bezahlt.
     */
    private static void duplicatedInstructions(BlockDefinition block, String instructions, int i,
                                               String name, List<AnalyzerFinding> out) {
        if (instructions == null || instructions.isBlank()) return;
        Map<String, List<String>> prompts = new LinkedHashMap<>();
        prompts.put("", BlockText.instructions(block));
        prompts.putAll(BlockText.nestedInstructions(block));
        for (String paragraph : instructions.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.length() < 80) continue;
            for (Map.Entry<String, List<String>> source : prompts.entrySet()) {
                if (source.getValue().stream().anyMatch(prompt -> prompt.contains(trimmed))) {
                    String where = source.getKey().isEmpty() ? "" : " im verschachtelten Block '" + source.getKey() + "'";
                    String whereEn = source.getKey().isEmpty() ? "" : " in the nested block '" + source.getKey() + "'";
                    out.add(AnalyzerFinding.of("duplicated-instructions", Severity.MEDIUM, i, name,
                            "wiederholt" + where + " wörtlich einen Absatz aus workflow.instructions ("
                                    + trimmed.length() + " Zeichen) — die Regeln gehen zweimal in den Kontext",
                            "repeats" + whereEn + " a paragraph of workflow.instructions word for word ("
                                    + trimmed.length() + " characters) — the rules go into the context twice"));
                    return;
                }
            }
        }
    }

    private static void datedModel(BlockDefinition block, int i, String name, List<AnalyzerFinding> out) {
        String model = BlockText.directValue(block, "model");
        if (model != null && DATED_MODEL.matcher(model).find()) {
            out.add(AnalyzerFinding.of("dated-model-id", Severity.LOW, i, name,
                    "nennt die datierte Modell-ID \"" + model
                            + "\" statt eines Alias (opus/sonnet/haiku/fable) — datierte IDs veralten still",
                    "names the dated model id \"" + model
                            + "\" instead of an alias (opus/sonnet/haiku/fable) — dated ids go stale silently"));
        }
    }
}
