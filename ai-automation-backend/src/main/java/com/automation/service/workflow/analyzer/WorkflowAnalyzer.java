package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * meldet, er blockiert nicht. Seit TODO 326d5987 läuft er außerdem nach jeder KI-Erzeugung einer Definition
 * ({@link GeneratedWorkflowCheck}): Befunde gehen an die KI zurück oder in das Ergebnis, nie in den Speicherweg.
 *
 * <p><b>Was er heute prüft</b> — fünfzehn Regeln, jede am Text entscheidbar, eine davon am Text samt
 * den Metadaten der Definition, drei am Block-Config-Schema, das auch der Editor nutzt:
 * <ul>
 *   <li>{@code unknown-block-reference} — Verweis auf einen Block, den es nicht gibt</li>
 *   <li>{@code chain-only-placeholder} — Chain-Variable in einem einzeln laufenden Workflow</li>
 *   <li>{@code audit-only-placeholder} — {@code {{ audit_findings }}} in einem Workflow ohne gültiges
 *       {@code metadata.audit}: die Variable gibt es dort nicht</li>
 *   <li>{@code unwired-output} — erzeugte Ausgabe, die kein späterer Block liest</li>
 *   <li>{@code absolute-home-path} — Pfad mit Klarnamen unter {@code /home/…}</li>
 *   <li>{@code missing-strict-mode} — Shell-Block ohne {@code set -euo pipefail}</li>
 *   <li>{@code duplicated-instructions} — KI-Auftrag des Blocks ({@code command}, {@code goal},
 *       {@code input} … je Typ) oder eines verschachtelten Blocks (switch-Case) wiederholt
 *       {@code workflow.instructions}</li>
 *   <li>{@code dated-model-id} — datierte Modell-ID statt Alias</li>
 *   <li>{@code on-no-without-reject-option} — Approval mit {@code on_no}, aber ohne Option, die ablehnt</li>
 *   <li>{@code items-command-without-source} — foreach mit {@code items_command}, aber ohne
 *       {@code items_source: "command"}: der Befehl läuft nie</li>
 *   <li>{@code unknown-config-key} — Config-Schlüssel, den kein Executor liest (auch in verschachtelten Blöcken)</li>
 *   <li>{@code missing-required-config-key} — Pflichtschlüssel des Blocktyps fehlt</li>
 *   <li>{@code invalid-config-value} — Wert außerhalb der festen Werte, etwa {@code on_failure: "fail"}</li>
 *   <li>{@code self-api-call} — Block ruft die REST-API der eigenen Instanz auf (außer den schlüsselfreien
 *       Sonden); im Shell-Code HIGH, im KI-Auftrag MEDIUM zum Prüfen ({@link SelfAccessRules})</li>
 *   <li>{@code direct-db-access} — Block liest die Plattform-Datenbank ({@code app_settings},
 *       {@code automation.db} mit Lesezugriff) — HIGH; {@code sqlite3} allein MEDIUM zum Prüfen</li>
 * </ul>
 *
 * <p><b>Was er bewusst nicht prüft.</b> Drei Regeln des Tickets brauchen mehr als den Text und
 * sind deshalb nicht enthalten: redundante KI-Aufrufe (verlangt ein Ähnlichkeitsurteil über zwei
 * Aufträge), die Sparsamkeits-Leiter („hier hätte ein Shell-Block gereicht" ist eine Bewertung,
 * keine Messung) und zu knappe Timeouts (braucht Laufzeitdaten aus {@code step_results}). Eine
 * Regel, die raten muss, erzeugt Rauschen — und ein Prüfwerkzeug, dem man nicht glaubt, wird
 * abgeschaltet.
 *
 * <p><b>Eine vierte Regel ist daran gescheitert</b> und wieder entfernt worden (TODO 8e5d13a9):
 * {@code gate-order-without-format} sollte Prompts finden, die einen {@code gate_status}-Marker
 * bestellen, ohne zu sagen, dass er am Zeilenanfang stehen muss. Der Bestandslauf ergab 22 Treffer
 * in 15 ausgelieferten Definitionen — und bei der Durchsicht war <b>kein einziger</b> echt: 18 waren
 * Shell-Blöcke, die den Marker selbst schreiben ({@code echo "gate_status: GREEN"}), die übrigen
 * Prompts <i>erwähnten</i> ihn bloß. Einer sagte sogar ausdrücklich „Gib KEINEN gate_status aus".
 * Die Unterscheidung zwischen „bestellt einen Marker" und „redet über Marker" verlangt
 * Textverständnis, nicht Mustersuche. Wer sie wieder aufnimmt, braucht dafür einen anderen Weg —
 * etwa ein Feld in der Definition, das den Gate-Auftrag ausdrücklich macht.
 */
@Service
public class WorkflowAnalyzer {

    /** Befunde einer Definition, schwerste zuerst und innerhalb gleicher Schwere in Blockreihenfolge. */
    public List<AnalyzerFinding> analyze(Workflow workflow) {
        if (workflow == null || workflow.getBlocks() == null || workflow.getBlocks().isEmpty()) {
            return List.of();
        }
        return analyze(workflow.getBlocks(), workflow.getInstructions(), workflow.getMetadata());
    }

    /** Wie {@link #analyze(Workflow)}, aber auf einer losen Blockliste ohne Metadaten — also kein Audit-Workflow. */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions) {
        return analyze(blocks, instructions, Map.of());
    }

    /**
     * Wie {@link #analyze(Workflow)}, auf einer losen Blockliste samt den Metadaten der Definition — für
     * Pool-Dateien und Tests. {@code metadata.audit} entscheidet, ob {@code {{ audit_findings }}} belegt wird.
     */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions,
                                         Map<String, Object> metadata) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<AnalyzerFinding> findings = new ArrayList<>();
        WiringRules.apply(blocks, metadata != null ? metadata : Map.of(), findings);
        HygieneRules.apply(blocks, instructions, findings);
        ApprovalRules.apply(blocks, findings);
        ForEachRules.apply(blocks, findings);
        ConfigKeyRules.apply(blocks, findings);
        SelfAccessRules.apply(blocks, findings);
        findings.sort((a, b) -> a.severity() != b.severity()
                ? a.severity().compareTo(b.severity())
                : Integer.compare(a.blockIndex(), b.blockIndex()));
        return List.copyOf(findings);
    }
}
