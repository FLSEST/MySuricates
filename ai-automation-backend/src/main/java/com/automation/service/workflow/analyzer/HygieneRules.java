package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Regeln zur <b>Hygiene</b> einzelner Blöcke: Fehlerabbruch im Shell-Block, Pfade mit Klarnamen,
 * doppelte Anweisungen, ein Gate-Auftrag ohne Formatvorgabe, datierte Modell-IDs.
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

    /** Der Parser ist zeilenverankert — vor dem Marker darf kein Wort stehen. */
    private static final Pattern GATE_ORDER = Pattern.compile("gate[_-]status", Pattern.CASE_INSENSITIVE);
    private static final Pattern GATE_LINE_HINT = Pattern.compile(
            "eigene[nr]? Zeile|own line|separate line|Zeilenanfang|at the start of a line|^\\s*gate[_-]status\\s*:",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    static void apply(List<BlockDefinition> blocks, String workflowInstructions, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            String name = block.getName();
            String all = BlockText.joined(block);

            shellRules(block, i, name, all, out);
            duplicatedInstructions(block, workflowInstructions, i, name, out);
            gateOrderWithoutFormat(all, i, name, out);
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
     * Der Block-Prompt wiederholt wörtlich, was ohnehin über {@code workflow.instructions} in
     * jeden Aufruf geht: dieselben Regeln zweimal im Kontext, doppelt bezahlt.
     */
    private static void duplicatedInstructions(BlockDefinition block, String instructions, int i,
                                               String name, List<AnalyzerFinding> out) {
        if (instructions == null || instructions.isBlank()) return;
        String prompt = BlockText.instruction(block);
        if (prompt == null || prompt.isBlank()) return;
        for (String paragraph : instructions.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.length() >= 80 && prompt.contains(trimmed)) {
                out.add(AnalyzerFinding.of("duplicated-instructions", Severity.MEDIUM, i, name,
                        "wiederholt wörtlich einen Absatz aus workflow.instructions ("
                                + trimmed.length() + " Zeichen) — die Regeln gehen zweimal in den Kontext"));
                return;
            }
        }
    }

    /**
     * Der Prompt bestellt ein {@code gate_status}, sagt aber nicht, dass der Marker allein auf
     * seiner Zeile stehen muss. {@code GateStatusOutputParser} ist zeilenverankert; ein
     * „## Verdict: gate_status: YELLOW" fällt durch und der Lauf gilt als urteilslos.
     */
    private static void gateOrderWithoutFormat(String all, int i, String name, List<AnalyzerFinding> out) {
        if (!GATE_ORDER.matcher(all).find()) return;
        if (GATE_LINE_HINT.matcher(all).find()) return;
        out.add(AnalyzerFinding.of("gate-order-without-format", Severity.MEDIUM, i, name,
                "verlangt einen gate_status-Marker, nennt aber keine Formatvorgabe — der Parser "
                        + "liest ihn nur am Zeilenanfang, sonst bleibt der Lauf ohne Urteil"));
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
