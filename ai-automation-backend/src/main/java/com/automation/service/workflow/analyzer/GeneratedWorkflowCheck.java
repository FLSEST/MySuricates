package com.automation.service.workflow.analyzer;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *       {@code MEDIUM}/{@code LOW} (Kostenregel). Ist die Antwort unbrauchbar oder hat die korrigierte Fassung
 *       mehr {@code HIGH}-Befunde, gilt die ursprüngliche.</li>
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
     * für Ergebnisse, die der Nutzer sieht.
     */
    public static List<String> summaryLines(List<AnalyzerFinding> findings) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < findings.size() && i < MAX_LINES; i++) {
            lines.add(line(findings.get(i)));
        }
        if (findings.size() > MAX_LINES) {
            lines.add("… und " + (findings.size() - MAX_LINES) + " weitere");
        }
        return lines;
    }

    /** {@link #summaryLines} als Aufzählung, eine Zeile je Befund; leer ohne Befunde. */
    public static String format(List<AnalyzerFinding> findings) {
        return String.join("\n", summaryLines(findings).stream().map(l -> "- " + l).toList());
    }

    /** Eine Befundzeile: Schwere, Regel, Block (1-basiert, mit Name) und Detail. */
    static String line(AnalyzerFinding f) {
        String where;
        if (f.blockIndex() < 0) {
            where = "Definition";
        } else {
            where = "Block " + (f.blockIndex() + 1)
                    + (f.blockName() != null && !f.blockName().isBlank() ? " „" + f.blockName() + "“" : "");
        }
        return f.severity() + " " + f.rule() + " — " + where + ": " + f.detail();
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
            corrected = parseBlocks(model.ask(correctionPrompt(blocks, before)));
        } catch (Exception e) {
            log.warn("Baufehler-Korrektur: KI-Aufruf fehlgeschlagen, ursprüngliche Fassung bleibt: {}", e.getMessage());
            corrected = List.of();
        }
        if (corrected.isEmpty()) {
            log.info("Baufehler-Korrektur: Antwort unbrauchbar, ursprüngliche Fassung bleibt ({} HIGH)", highBefore);
            return new Correction(blocks, before, true, false);
        }
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
                Fix every finding marked HIGH. Keep everything else as it is: same purpose, same block order,
                no additional blocks unless a finding requires one. Block numbers count from 1 in array order.

                CURRENT BLOCKS (JSON):
                %s

                BUILD CHECK FINDINGS:
                %s

                Output ONLY the corrected blocks array as JSON. Do not wrap it in markdown code fences.
                """.formatted(blocksJson, format(findings));
    }

    /**
     * Liest eine Blockliste aus einer Modellantwort: ein JSON-Array oder ein Objekt mit {@code blocks},
     * gern in Code-Fences. Leer, wenn nichts Brauchbares darin steht.
     */
    List<BlockDefinition> parseBlocks(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        try {
            String text = stripFences(response.trim());
            int arr = text.indexOf('[');
            int obj = text.indexOf('{');
            JsonNode node;
            if (arr >= 0 && (obj < 0 || arr < obj)) {
                node = JsonRepairUtil.readTreeWithRepair(text.substring(arr, text.lastIndexOf(']') + 1));
            } else if (obj >= 0) {
                node = JsonRepairUtil.readTreeWithRepair(text.substring(obj, text.lastIndexOf('}') + 1)).path("blocks");
            } else {
                return List.of();
            }
            if (!node.isArray()) {
                return List.of();
            }
            List<BlockDefinition> blocks = new ArrayList<>();
            for (JsonNode b : node) {
                if (!b.isObject()) {
                    return List.of();
                }
                blocks.add(toBlock(b));
            }
            return blocks;
        } catch (Exception e) {
            log.debug("Baufehler-Korrektur: Antwort nicht lesbar: {}", e.getMessage());
            return List.of();
        }
    }

    private BlockDefinition toBlock(JsonNode b) {
        BlockDefinition block = new BlockDefinition();
        block.setType(b.path("type").asText("simple"));
        if (b.hasNonNull("name")) {
            block.setName(b.get("name").asText());
        }
        if (b.path("config").isObject()) {
            block.setConfig(objectMapper.convertValue(b.get("config"), new TypeReference<LinkedHashMap<String, Object>>() {}));
        }
        if (b.path("requiredCapabilities").isArray()) {
            block.setRequiredCapabilities(objectMapper.convertValue(b.get("requiredCapabilities"), new TypeReference<>() {}));
        }
        if (b.path("skillIds").isArray()) {
            block.setSkillIds(objectMapper.convertValue(b.get("skillIds"), new TypeReference<>() {}));
        }
        return block;
    }

    private static String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
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
