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
 * Construction-error check for definitions an AI created or changed (TODO 326d5987) - the one
 * place through which assistant, factory, wishes, dialog import and conversion run the {@link WorkflowAnalyzer}
 * after generation.
 *
 * <ul>
 *   <li>{@link #findings} returns the findings from {@code MEDIUM} up, {@code HIGH} first; {@code LOW} is hygiene
 *       and no reason to bother an AI or the user.</li>
 *   <li>{@link #format} turns them into a compact text: per line severity, rule, block, detail - at most
 *       {@value #MAX_LINES} lines and a "… und N weitere".</li>
 *   <li>{@link #correctOnce} gives the AI <b>exactly one</b> correction round for {@code HIGH} findings; never for
 *       {@code MEDIUM}/{@code LOW} (cost rule). If the answer is unusable, has a different structure
 *       (block count, block type per position, number of nested blocks per container) or if the corrected
 *       version has more {@code HIGH} findings, the original applies. If the correction omits {@code name},
 *       {@code skillIds} or {@code requiredCapabilities} of a block, they come from the original.</li>
 *   <li>{@link #reviewRepair} applies the same rules to a repair that the caller itself fetched from the AI
 *       (factory auto-fix, TODO 99a9dfb0) - without its own AI call.</li>
 * </ul>
 *
 * <p>The check never changes anything itself and does not block saving. The AI call comes from the caller
 * (same model, same provider as its original call) and must not run inside a transaction;
 * {@link #reportRemaining} writes to the activity log and therefore belongs after the transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeneratedWorkflowCheck {

    /** Maximum number of finding lines in {@link #format}/{@link #summaryLines}; the rest is counted. */
    static final int MAX_LINES = 10;

    private static final String SOURCE = "GeneratedWorkflowCheck";
    private static final String DE = "de";
    private static final String EN = "en";

    private final WorkflowAnalyzer analyzer;
    private final ObjectMapper objectMapper;
    private final ActivityLogService activityLogService;

    /** The caller's AI call: prompt in, answer text out. */
    @FunctionalInterface
    public interface ModelCall {
        String ask(String prompt) throws Exception;
    }

    /** Result of {@link #correctOnce}: the blocks that get saved, and their findings from MEDIUM up. */
    public record Correction(List<BlockDefinition> blocks, List<AnalyzerFinding> remaining,
                             boolean attempted, boolean applied) {
    }

    /**
     * Result of {@link #reviewRepair}: accepted ({@code rejection == null}; {@code blocks} is the completed
     * repair) or rejected with a reason ({@code blocks} is the original). {@code findings} are the findings from
     * MEDIUM up of the version in {@code blocks}.
     */
    public record RepairReview(List<BlockDefinition> blocks, List<AnalyzerFinding> findings, String rejection) {

        static RepairReview rejected(List<BlockDefinition> original, List<AnalyzerFinding> findings, String reason) {
            return new RepairReview(original, findings, reason);
        }

        public boolean accepted() {
            return rejection == null;
        }
    }

    /** Findings from MEDIUM up of a saved definition (including metadata), HIGH first. */
    public List<AnalyzerFinding> findings(Workflow workflow) {
        return relevant(analyzer.analyze(workflow));
    }

    /** Findings from MEDIUM up of a loose block list, HIGH first. */
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
     * The findings as lines, at most {@value #MAX_LINES} and, if there are more, a closing line "… und N weitere" -
     * for results the user sees. German; {@link #summaryLines(List, String)} for another language.
     */
    public static List<String> summaryLines(List<AnalyzerFinding> findings) {
        return summaryLines(findings, DE);
    }

    /**
     * Like {@link #summaryLines(List)}, in {@code language} ({@code "en"}, otherwise German) - detail text and
     * closing line ("… and N more") from {@link AnalyzerFinding#detail(String)} (TODO 81773d7b).
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

    /** {@link #summaryLines} as a list, one line per finding; empty without findings. German. */
    public static String format(List<AnalyzerFinding> findings) {
        return format(findings, DE);
    }

    /** {@link #format(List)} in {@code language} ({@code "en"}, otherwise German). */
    public static String format(List<AnalyzerFinding> findings, String language) {
        return String.join("\n", summaryLines(findings, language).stream().map(l -> "- " + l).toList());
    }

    /** One finding line: severity, rule, block (1-based, with name) and detail - German. */
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
     * At most one correction round: only for HIGH findings does it ask {@code model} (prompt → answer text)
     * exactly once for a corrected block list. Never throws - a model error counts as an
     * unusable answer.
     *
     * @param model the caller's AI call, with its provider and model; runs outside any transaction
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
        RepairReview review = review(blocks, before, corrected, instructions);
        if (!review.accepted()) {
            log.warn("Baufehler-Korrektur verworfen, {}; ursprüngliche Fassung bleibt", review.rejection());
            return new Correction(blocks, before, true, false);
        }
        log.info("Baufehler-Korrektur übernommen: HIGH {} → {}", highBefore, highCount(review.findings()));
        return new Correction(review.blocks(), review.findings(), true, true);
    }

    /**
     * Checks a block list an AI made from {@code original} - e.g. the factory's auto-fix repair
     * (TODO 99a9dfb0) - by the same rules as the correction round of {@link #correctOnce}, without asking the AI
     * itself: reject if the structure did not stay the same ({@link CorrectionAnswer#shapeChange}) or the
     * repair has more {@code HIGH} findings than the original. Omitted {@code name}, {@code skillIds} and
     * {@code requiredCapabilities} come from the original ({@code repaired} is completed for that).
     */
    public RepairReview reviewRepair(List<BlockDefinition> original, List<BlockDefinition> repaired, String instructions) {
        return review(original, findings(original, instructions), repaired, instructions);
    }

    private RepairReview review(List<BlockDefinition> original, List<AnalyzerFinding> before,
                                List<BlockDefinition> repaired, String instructions) {
        String shapeChange = CorrectionAnswer.shapeChange(original, repaired);
        if (shapeChange != null) {
            // Typical error: the model returns only the repaired block - the others silently dropped out.
            return RepairReview.rejected(original, before, "Struktur geändert: " + shapeChange);
        }
        CorrectionAnswer.keepOmittedFields(original, repaired);
        List<AnalyzerFinding> after = findings(repaired, instructions);
        long highBefore = highCount(before);
        long highAfter = highCount(after);
        if (highAfter > highBefore) {
            return RepairReview.rejected(original, before, "mehr HIGH-Befunde (" + highBefore + " → " + highAfter + ")");
        }
        return new RepairReview(repaired, after, null);
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
                """.formatted(blocksJson, format(findings, "en"));   // English prompt, English findings
    }

    /**
     * Reports remaining findings from MEDIUM up as WARN to the activity log, on the workflow. Nothing without findings.
     * Call outside any transaction; a failure while writing does not abort the path.
     *
     * @param origin who created the workflow, e.g. "Factory" or "Wunsch"
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
