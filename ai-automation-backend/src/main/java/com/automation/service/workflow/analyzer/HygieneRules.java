package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.List;
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
            String all = BlockText.joined(block);

            shellRules(block, i, name, all, out);
            duplicatedInstructions(block, workflowInstructions, i, name, out);
            datedModel(block, i, name, out);
        }
    }

    private static void shellRules(BlockDefinition block, int i, String name, String all,
                                   List<AnalyzerFinding> out) {
        var matcher = HOME_PATH.matcher(all);
        if (matcher.find()) {
            out.add(AnalyzerFinding.of("absolute-home-path", Severity.HIGH, i, name,
                    "enthält einen Pfad unter /home/" + matcher.group(1)
                            + " — trägt den Klarnamen und bricht auf jedem anderen Rechner"));
        }
        if (!"shell".equals(block.getType())) return;
        String command = BlockText.directValue(block, "command");
        if (command != null && !command.isBlank() && !command.contains("set -e") && !command.contains("set -o errexit")) {
            out.add(AnalyzerFinding.of("missing-strict-mode", Severity.LOW, i, name,
                    "Shell-Block ohne 'set -euo pipefail' — ein Fehler in der Mitte bleibt unbemerkt, "
                            + "der Block endet trotzdem mit 0"));
        }
    }

    /**
     * Der KI-Auftrag des Blocks ({@link BlockText#instructions}) wiederholt wörtlich, was ohnehin
     * über {@code workflow.instructions} in jeden Aufruf geht: dieselben Regeln zweimal im Kontext,
     * doppelt bezahlt.
     */
    private static void duplicatedInstructions(BlockDefinition block, String instructions, int i,
                                               String name, List<AnalyzerFinding> out) {
        if (instructions == null || instructions.isBlank()) return;
        List<String> prompts = BlockText.instructions(block);
        if (prompts.isEmpty()) return;
        for (String paragraph : instructions.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.length() >= 80 && prompts.stream().anyMatch(prompt -> prompt.contains(trimmed))) {
                out.add(AnalyzerFinding.of("duplicated-instructions", Severity.MEDIUM, i, name,
                        "wiederholt wörtlich einen Absatz aus workflow.instructions ("
                                + trimmed.length() + " Zeichen) — die Regeln gehen zweimal in den Kontext"));
                return;
            }
        }
    }

    private static void datedModel(BlockDefinition block, int i, String name, List<AnalyzerFinding> out) {
        String model = BlockText.directValue(block, "model");
        if (model != null && DATED_MODEL.matcher(model).find()) {
            out.add(AnalyzerFinding.of("dated-model-id", Severity.LOW, i, name,
                    "nennt die datierte Modell-ID \"" + model
                            + "\" statt eines Alias (opus/sonnet/haiku/fable) — datierte IDs veralten still"));
        }
    }
}
