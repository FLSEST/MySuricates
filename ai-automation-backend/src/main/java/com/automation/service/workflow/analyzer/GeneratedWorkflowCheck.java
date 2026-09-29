package com.automation.service.workflow.analyzer;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.activitylog.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Baufehler-Prüfung für Definitionen, die eine KI erzeugt oder geändert hat (TODO 326d5987) — die eine
 * Stelle, über die Assistent, Factory, Wünsche, Dialog-Import und Konvertierung den {@link WorkflowAnalyzer}
 * nach der Erzeugung laufen lassen.
 *
 * <ul>
 *   <li>{@link #findings} liefert die Befunde ab {@code MEDIUM}, {@code HIGH} zuerst; {@code LOW} ist Hygiene
 *       und kein Anlass, eine KI oder den Nutzer zu behelligen.</li>
 *   <li>{@link #format} macht daraus einen kompakten Text: je Zeile Schwere, Regel, Block, Detail — höchstens
 *       {@value #MAX_LINES} Zeilen und ein „… und N weitere“.</li>
 *   <li>{@link #correctOnce} gibt der KI bei {@code HIGH}-Befunden <b>genau eine</b> Korrekturrunde; nie bei
 *       {@code MEDIUM}/{@code LOW} (Kostenregel). Ist die Antwort unbrauchbar, hat sie eine andere Struktur
 *       (Blockzahl, Blocktyp je Position, Zahl der verschachtelten Blöcke je Container) oder hat die korrigierte
 *       Fassung mehr {@code HIGH}-Befunde, gilt die ursprüngliche. Lässt die Korrektur {@code name},
 *       {@code skillIds} oder {@code requiredCapabilities} eines Blocks weg, kommen sie aus dem Original.</li>
 * </ul>
 *
 * <p>Die Prüfung ändert nie selbst etwas und blockiert kein Speichern. Der KI-Aufruf kommt vom Aufrufer
 * (gleiches Modell, gleicher Provider wie sein ursprünglicher Aufruf) und darf nicht in einer Transaktion
 * laufen; {@link #reportRemaining} schreibt ins Activity-Log und gehört deshalb hinter die Transaktion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeneratedWorkflowCheck {

    /** Höchstzahl der Befundzeilen in {@link #format}/{@link #summaryLines}; der Rest wird gezählt. */
    static final int MAX_LINES = 10;

    private static final String SOURCE = "GeneratedWorkflowCheck";
    private static final String DE = "de";
    private static final String EN = "en";

    private final WorkflowAnalyzer analyzer;
    private final ObjectMapper objectMapper;
    private final ActivityLogService activityLogService;

    /** Der KI-Aufruf des Aufrufers: Prompt hinein, Antworttext heraus. */
    @FunctionalInterface
    public interface ModelCall {
        String ask(String prompt) throws Exception;
    }

    /** Ergebnis von {@link #correctOnce}: die Blöcke, die gespeichert werden, und ihre Befunde ab MEDIUM. */
    public record Correction(List<BlockDefinition> blocks, List<AnalyzerFinding> remaining,
                             boolean attempted, boolean applied) {
    }

    /** Befunde ab MEDIUM einer gespeicherten Definition (samt Metadaten), HIGH zuerst. */
    public List<AnalyzerFinding> findings(Workflow workflow) {
        return relevant(analyzer.analyze(workflow));
    }

    /** Befunde ab MEDIUM einer losen Blockliste, HIGH zuerst. */
    public List<AnalyzerFinding> findings(List<BlockDefinition> blocks, String instructions) {
        return relevant(analyzer.analyze(blocks, instructions));
    }

    static List<AnalyzerFinding> relevant(List<AnalyzerFinding> all) {
        return all.stream()
                .filter(f -> f.severity() != AnalyzerFinding.Severity.LOW)
                .sorted(Comparator.comparing(AnalyzerFinding::severity))
                .toList();
    }

    public static long highCount(List<AnalyzerFinding> findings) {
        return findings.stream().filter(f -> f.severity() == AnalyzerFinding.Severity.HIGH).count();
    }

    /**
     * Die Befunde als Zeilen, höchstens {@value #MAX_LINES} und bei mehr eine Schlusszeile „… und N weitere“ —
     * für Ergebnisse, die der Nutzer sieht. Deutsch; {@link #summaryLines(List, String)} für eine andere Sprache.
     */
    public static List<String> summaryLines(List<AnalyzerFinding> findings) {
        return summaryLines(findings, DE);
    }

    /**
     * Wie {@link #summaryLines(List)}, in {@code language} ({@code "en"}, sonst deutsch) — Detailtext und
     * Schlusszeile („… and N more“) aus {@link AnalyzerFinding#detail(String)} (TODO 81773d7b).
     */
    public static List<String> summaryLines(List<AnalyzerFinding> findings, String language) {
        boolean en = EN.equalsIgnoreCase(language);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < findings.size() && i < MAX_LINES; i++) {
            lines.add(line(findings.get(i), language));
        }
        if (findings.size() > MAX_LINES) {
            int more = findings.size() - MAX_LINES;
            lines.add(en ? "… and " + more + " more" : "… und " + more + " weitere");
        }
        return lines;
    }

    /** {@link #summaryLines} als Aufzählung, eine Zeile je Befund; leer ohne Befunde. Deutsch. */
    public static String format(List<AnalyzerFinding> findings) {
        return format(findings, DE);
    }

    /** {@link #format(List)} in {@code language} ({@code "en"}, sonst deutsch). */
    public static String format(List<AnalyzerFinding> findings, String language) {
        return String.join("\n", summaryLines(findings, language).stream().map(l -> "- " + l).toList());
    }

    /** Eine Befundzeile: Schwere, Regel, Block (1-basiert, mit Name) und Detail — deutsch. */
    static String line(AnalyzerFinding f) {
        return line(f, DE);
    }

    static String line(AnalyzerFinding f, String language) {
        boolean en = EN.equalsIgnoreCase(language);
        String where;
        if (f.blockIndex() < 0) {
            where = "Definition";
        } else {
            boolean named = f.blockName() != null && !f.blockName().isBlank();
            where = "Block " + (f.blockIndex() + 1)
                    + (!named ? "" : en ? " \"" + f.blockName() + "\"" : " „" + f.blockName() + "“");
        }
        return f.severity() + " " + f.rule() + " — " + where + ": " + f.detail(language);
    }

    /**
     * Höchstens eine Korrekturrunde: nur bei HIGH-Befunden fragt sie {@code model} (Prompt → Antworttext)
     * genau einmal nach einer korrigierten Blockliste. Wirft nie — ein Fehler des Modells zählt als
     * unbrauchbare Antwort.
     *
     * @param model der KI-Aufruf des Aufrufers, mit dessen Provider und Modell; läuft außerhalb jeder Transaktion
     */
    public Correction correctOnce(List<BlockDefinition> blocks, String instructions, ModelCall model) {
        List<AnalyzerFinding> before = findings(blocks, instructions);
        long highBefore = highCount(before);
        if (highBefore == 0) {
            return new Correction(blocks, before, false, false);
        }
        List<BlockDefinition> corrected;
        try {
            corrected = CorrectionAnswer.parse(model.ask(correctionPrompt(blocks, before)), objectMapper);
        } catch (Exception e) {
            log.warn("Baufehler-Korrektur: KI-Aufruf fehlgeschlagen, ursprüngliche Fassung bleibt: {}", e.getMessage());
            corrected = List.of();
        }
        if (corrected.isEmpty()) {
            log.info("Baufehler-Korrektur: Antwort unbrauchbar, ursprüngliche Fassung bleibt ({} HIGH)", highBefore);
            return new Correction(blocks, before, true, false);
        }
        String shapeChange = CorrectionAnswer.shapeChange(blocks, corrected);
        if (shapeChange != null) {
            // Typischer Fehler: das Modell liefert nur den reparierten Block zurück — die übrigen fielen still weg.
            log.warn("Baufehler-Korrektur verworfen, Struktur geändert: {} (Blöcke vorher {}, nachher {}); "
                    + "ursprüngliche Fassung bleibt", shapeChange, blocks.size(), corrected.size());
            return new Correction(blocks, before, true, false);
        }
        CorrectionAnswer.keepOmittedFields(blocks, corrected);
        List<AnalyzerFinding> after = findings(corrected, instructions);
        if (highCount(after) > highBefore) {
            log.info("Baufehler-Korrektur: korrigierte Fassung hat mehr HIGH-Befunde ({} > {}), ursprüngliche bleibt",
                    highCount(after), highBefore);
            return new Correction(blocks, before, true, false);
        }
        log.info("Baufehler-Korrektur übernommen: HIGH {} → {}", highBefore, highCount(after));
        return new Correction(corrected, after, true, true);
    }

    String correctionPrompt(List<BlockDefinition> blocks, List<AnalyzerFinding> findings) {
        String blocksJson;
        try {
            blocksJson = objectMapper.writeValueAsString(blocks);
        } catch (Exception e) {
            throw new IllegalStateException("Blöcke nicht serialisierbar: " + e.getMessage(), e);
        }
        return """
                The workflow blocks you produced fail the platform's deterministic build check.
                Fix every finding marked HIGH. Return ALL blocks, not only the fixed ones: same number of blocks,
                same order, same type at every position, same block names. Keep everything else as it is.
                Block numbers count from 1 in array order.

                CURRENT BLOCKS (JSON):
                %s

                BUILD CHECK FINDINGS:
                %s

                Output ONLY the corrected blocks array as JSON. Do not wrap it in markdown code fences.
                """.formatted(blocksJson, format(findings));
    }

    /**
     * Meldet verbliebene Befunde ab MEDIUM als WARN ins Activity-Log, am Workflow. Ohne Befunde nichts.
     * Außerhalb jeder Transaktion aufrufen; ein Fehler beim Schreiben bricht den Weg nicht ab.
     *
     * @param origin wer den Workflow erzeugt hat, etwa „Factory“ oder „Wunsch“
     */
    public void reportRemaining(String workflowId, String workflowName, String origin, List<AnalyzerFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        try {
            activityLogService.warn(LogCategory.SYSTEM,
                    "Baufehler-Prüfung (" + origin + "): Workflow „" + workflowName + "“ hat "
                            + findings.size() + " Befund(e) ab MEDIUM:\n" + format(findings),
                    SOURCE, null, workflowId);
        } catch (Exception e) {
            log.warn("Baufehler-Prüfung: Activity-Log-Eintrag für Workflow {} fehlgeschlagen: {}", workflowId, e.getMessage());
        }
    }
}
