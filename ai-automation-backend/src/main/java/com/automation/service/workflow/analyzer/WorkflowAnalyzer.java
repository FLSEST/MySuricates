package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Prüft eine Workflow-Definition deterministisch auf bekannte Baufehler — ohne KI-Aufruf.
 *
 * <p>Anlass: Die Analyse von PRD-07 (TODO 45509fcb) förderte sieben Mängel zutage, die <b>alle</b>
 * maschinell erkennbar waren. Keiner war ein inhaltlicher Fehler; es waren Baufehler, die seit
 * Monaten unbemerkt Geld und Laufzeit kosteten. Das ist die eigene Projektlehre — messen, bevor
 * eine KI bewertet — angewandt auf die eigenen Workflows.
 *
 * <p><b>Wirkungsort</b> (Entscheidung vom 2026-09-19): auf Abruf über die REST-API und als Test
 * über die ausgelieferten Pool-Definitionen. Der Speicherweg bleibt unangetastet — der Analyzer
 * meldet, er blockiert nicht.
 *
 * <p><b>Was er heute prüft</b> — sieben Regeln, jede eindeutig am Text entscheidbar:
 * <ul>
 *   <li>{@code unknown-block-reference} — Verweis auf einen Block, den es nicht gibt</li>
 *   <li>{@code chain-only-placeholder} — Chain-Variable in einem einzeln laufenden Workflow</li>
 *   <li>{@code unwired-output} — erzeugte Ausgabe, die kein späterer Block liest</li>
 *   <li>{@code absolute-home-path} — Pfad mit Klarnamen unter {@code /home/…}</li>
 *   <li>{@code missing-strict-mode} — Shell-Block ohne {@code set -euo pipefail}</li>
 *   <li>{@code duplicated-instructions} — Block-Prompt wiederholt {@code workflow.instructions}</li>
 *   <li>{@code gate-order-without-format} — Gate-Auftrag ohne Formatvorgabe</li>
 *   <li>{@code dated-model-id} — datierte Modell-ID statt Alias</li>
 * </ul>
 *
 * <p><b>Was er bewusst nicht prüft.</b> Drei Regeln des Tickets brauchen mehr als den Text und
 * sind deshalb nicht enthalten: redundante KI-Aufrufe (verlangt ein Ähnlichkeitsurteil über zwei
 * Aufträge), die Sparsamkeits-Leiter („hier hätte ein Shell-Block gereicht" ist eine Bewertung,
 * keine Messung) und zu knappe Timeouts (braucht Laufzeitdaten aus {@code step_results}). Eine
 * Regel, die raten muss, erzeugt Rauschen — und ein Prüfwerkzeug, dem man nicht glaubt, wird
 * abgeschaltet.
 */
@Service
public class WorkflowAnalyzer {

    /** Befunde einer Definition, schwerste zuerst und innerhalb gleicher Schwere in Blockreihenfolge. */
    public List<AnalyzerFinding> analyze(Workflow workflow) {
        if (workflow == null || workflow.getBlocks() == null || workflow.getBlocks().isEmpty()) {
            return List.of();
        }
        return analyze(workflow.getBlocks(), workflow.getInstructions());
    }

    /** Wie {@link #analyze(Workflow)}, aber auf einer losen Blockliste — für Pool-Dateien und Tests. */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<AnalyzerFinding> findings = new ArrayList<>();
        WiringRules.apply(blocks, findings);
        HygieneRules.apply(blocks, instructions, findings);
        findings.sort((a, b) -> a.severity() != b.severity()
                ? a.severity().compareTo(b.severity())
                : Integer.compare(a.blockIndex(), b.blockIndex()));
        return List.copyOf(findings);
    }
}
