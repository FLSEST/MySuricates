package com.automation.service.workflow.analyzer;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.service.activitylog.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shared build-error check of AI-generated definitions (TODO 326d5987). */
class GeneratedWorkflowCheckTest {

    /** simple block with {@code prompt} instead of {@code command}: HIGH (required key missing) plus MEDIUM (unknown key). */
    static final String BAD_BLOCKS = "[{\"type\":\"simple\",\"config\":{\"prompt\":\"Fasse zusammen\"}}]";
    static final String GOOD_BLOCKS = "[{\"type\":\"simple\",\"config\":{\"command\":\"Fasse zusammen\"}}]";
    static final String WORSE_BLOCKS = "[{\"type\":\"simple\",\"config\":{\"prompt\":\"a\"}},"
            + "{\"type\":\"simple\",\"config\":{\"prompt\":\"b\"}}]";

    private final ActivityLogService activityLog = mock(ActivityLogService.class);
    private final GeneratedWorkflowCheck check =
            new GeneratedWorkflowCheck(new WorkflowAnalyzer(), new ObjectMapper(), activityLog);

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    static List<BlockDefinition> badBlocks() {
        return new ArrayList<>(List.of(block("simple", "zusammenfassung", Map.of("prompt", "Fasse zusammen"))));
    }

    private static AnalyzerFinding finding(AnalyzerFinding.Severity severity, int index) {
        return new AnalyzerFinding("rule-" + index, severity, index, null, "Detail " + index);
    }

    @Test
    void findingsLassenLowWegUndStellenHighNachVorn() {
        var blocks = List.of(
                block("shell", "messung", Map.of("command", "echo ok")),              // LOW only: missing-strict-mode
                block("simple", "report", Map.of("prompt", "Fasse {{ previous_output }} zusammen")));

        var findings = check.findings(blocks, null);

        assertThat(findings).extracting(AnalyzerFinding::severity)
                .doesNotContain(AnalyzerFinding.Severity.LOW)
                .startsWith(AnalyzerFinding.Severity.HIGH);
        assertThat(findings).extracting(AnalyzerFinding::rule)
                .contains("missing-required-config-key", "unknown-config-key")
                .doesNotContain("missing-strict-mode");
    }

    @Test
    void formatZeigtSchwereRegelBlockUndDetail() {
        var f = new AnalyzerFinding("missing-required-config-key", AnalyzerFinding.Severity.HIGH, 1, "report",
                "Pflichtschlüssel command fehlt");

        assertThat(GeneratedWorkflowCheck.format(List.of(f)))
                .isEqualTo("- HIGH missing-required-config-key — Block 2 „report“: Pflichtschlüssel command fehlt");
        assertThat(GeneratedWorkflowCheck.format(List.of(
                new AnalyzerFinding("x", AnalyzerFinding.Severity.MEDIUM, -1, null, "ganz"))))
                .isEqualTo("- MEDIUM x — Definition: ganz");
        assertThat(GeneratedWorkflowCheck.format(List.of())).isEmpty();
    }

    /** TODO 81773d7b: the same lines in English — detail from detailEn, English quotation marks and closing line. */
    @Test
    void formatAufEnglischNimmtDetailEn_undFaelltSonstAufDeutschZurueck() {
        var f = new AnalyzerFinding("missing-required-config-key", AnalyzerFinding.Severity.HIGH, 1, "report",
                "Pflichtschlüssel command fehlt", "required key command is missing");
        List<AnalyzerFinding> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(finding(AnalyzerFinding.Severity.MEDIUM, i));
        }

        assertThat(GeneratedWorkflowCheck.format(List.of(f), "en"))
                .isEqualTo("- HIGH missing-required-config-key — Block 2 \"report\": required key command is missing");
        assertThat(GeneratedWorkflowCheck.format(List.of(f), "de")).isEqualTo(GeneratedWorkflowCheck.format(List.of(f)));
        assertThat(GeneratedWorkflowCheck.summaryLines(many, "en")).last().isEqualTo("… and 2 more");
        assertThat(GeneratedWorkflowCheck.summaryLines(many, "en").get(0)).endsWith(": Detail 0");   // without detailEn
    }

    @Test
    void formatBegrenztAufZehnZeilenUndZaehltDenRest() {
        List<AnalyzerFinding> many = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            many.add(finding(AnalyzerFinding.Severity.MEDIUM, i));
        }

        List<String> lines = GeneratedWorkflowCheck.summaryLines(many);

        assertThat(lines).hasSize(GeneratedWorkflowCheck.MAX_LINES + 1);
        assertThat(lines.get(9)).contains("rule-9");
        assertThat(lines.get(10)).isEqualTo("… und 3 weitere");
        assertThat(GeneratedWorkflowCheck.summaryLines(many.subList(0, 10))).hasSize(10)
                .noneMatch(l -> l.startsWith("…"));
    }

    @Test
    void ohneHighKeinKorrekturaufruf() {
        AtomicInteger calls = new AtomicInteger();
        // Only MEDIUM (unknown key next to the required key): cost rule, no call
        var blocks = List.of(block("simple", null, Map.of("command", "x", "promt", "y")));

        var result = check.correctOnce(blocks, null, p -> {
            calls.incrementAndGet();
            return GOOD_BLOCKS;
        });

        assertThat(calls).hasValue(0);
        assertThat(result.attempted()).isFalse();
        assertThat(result.blocks()).isSameAs(blocks);
        assertThat(result.remaining()).extracting(AnalyzerFinding::rule).containsExactly("unknown-config-key");
    }

    @Test
    void highFuehrtZuGenauEinemAufrufMitDenBefundenImPrompt() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> prompt = new AtomicReference<>();

        var result = check.correctOnce(badBlocks(), null, p -> {
            calls.incrementAndGet();
            prompt.set(p);
            return "```json\n" + GOOD_BLOCKS + "\n```";
        });

        assertThat(calls).hasValue(1);
        assertThat(prompt.get())
                .contains("HIGH missing-required-config-key")
                .contains("required key command is missing")
                .doesNotContain("Pflichtschlüssel")
                .contains("\"prompt\":\"Fasse zusammen\"")
                .contains("Output ONLY the corrected blocks array");
        assertThat(result.attempted()).isTrue();
        assertThat(result.applied()).isTrue();
        assertThat(result.blocks()).singleElement()
                .satisfies(b -> assertThat(b.getConfig()).containsEntry("command", "Fasse zusammen"));
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void korrekturAlsObjektMitBlocksWirdGelesenUndNameBleibt() {
        var result = check.correctOnce(badBlocks(), null,
                p -> "Hier: {\"blocks\":[{\"type\":\"simple\",\"name\":\"zusammenfassung\",\"config\":{\"command\":\"x\"}}]}");

        assertThat(result.applied()).isTrue();
        assertThat(result.blocks()).singleElement().extracting(BlockDefinition::getName).isEqualTo("zusammenfassung");
    }

    @Test
    void unbrauchbareAntwortLaesstDieUrspruenglicheFassung() {
        var original = badBlocks();

        for (String answer : List.of("Tut mir leid, das kann ich nicht.", "[]", "[1, 2]", "{\"foo\":1}", "")) {
            var result = check.correctOnce(original, null, p -> answer);

            assertThat(result.attempted()).as(answer).isTrue();
            assertThat(result.applied()).as(answer).isFalse();
            assertThat(result.blocks()).as(answer).isSameAs(original);
            assertThat(GeneratedWorkflowCheck.highCount(result.remaining())).as(answer).isEqualTo(1);
        }
    }

    @Test
    void fehlerDesModellsZaehltAlsUnbrauchbar() {
        var original = badBlocks();

        var result = check.correctOnce(original, null, p -> {
            throw new java.io.IOException("CLI weg");
        });

        assertThat(result.applied()).isFalse();
        assertThat(result.blocks()).isSameAs(original);
    }

    @Test
    void mehrHighBefundeLassenDieUrspruenglicheFassung() {
        var original = badBlocks();

        var result = check.correctOnce(original, null, p -> WORSE_BLOCKS);

        assertThat(result.attempted()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.blocks()).isSameAs(original);
    }

    /** Broken block plus a healthy block after it. */
    private static List<BlockDefinition> badAndHealthy() {
        return new ArrayList<>(List.of(
                block("simple", "zusammenfassung", Map.of("prompt", "Fasse zusammen")),
                block("shell", "ablage", Map.of("command", "set -euo pipefail\necho ok"))));
    }

    @Test
    void korrekturOhneDenGesundenBlockLaesstDasOriginal() {
        var original = badAndHealthy();

        // typical error: only the repaired block comes back — fewer HIGH, but a block is missing
        var result = check.correctOnce(original, null, p -> GOOD_BLOCKS);

        assertThat(result.attempted()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.blocks()).isSameAs(original);
        assertThat(GeneratedWorkflowCheck.highCount(result.remaining())).isEqualTo(1);
    }

    @Test
    void korrekturMitGeaendertemBlocktypLaesstDasOriginal() {
        var original = badBlocks();

        var result = check.correctOnce(original, null,
                p -> "[{\"type\":\"shell\",\"config\":{\"command\":\"set -euo pipefail\\necho ok\"}}]");

        assertThat(result.applied()).isFalse();
        assertThat(result.blocks()).isSameAs(original);
    }

    @Test
    void korrekturOhneNameUebernimmtNameSkillsUndCapabilitiesAusDemOriginal() {
        var original = badAndHealthy();
        original.get(0).setSkillIds(new ArrayList<>(List.of("skill-1")));
        original.get(0).setRequiredCapabilities(new ArrayList<>(List.of("web")));

        var result = check.correctOnce(original, null, p -> "[{\"type\":\"simple\",\"config\":{\"command\":\"Fasse zusammen\"}},"
                + "{\"type\":\"shell\",\"config\":{\"command\":\"set -euo pipefail\\necho ok\"}}]");

        assertThat(result.applied()).isTrue();
        assertThat(result.blocks()).extracting(BlockDefinition::getName).containsExactly("zusammenfassung", "ablage");
        assertThat(result.blocks().get(0).getSkillIds()).containsExactly("skill-1");
        assertThat(result.blocks().get(0).getRequiredCapabilities()).containsExactly("web");
        assertThat(result.blocks().get(0).getConfig()).containsEntry("command", "Fasse zusammen");
    }

    @Test
    void reviewRepairWendetDieRegelnOhneKiAufrufAn() {
        // Factory auto-fix (TODO 99a9dfb0): the caller fetches the repair itself, checked as in correctOnce.
        var original = badAndHealthy();
        original.get(0).setSkillIds(new ArrayList<>(List.of("skill-1")));

        var fehlenderBlock = check.reviewRepair(original, badBlocks(), null);
        var schlechter = check.reviewRepair(badBlocks(), new ArrayList<>(List.of(
                block("simple", null, Map.of("prompt", "{{ block.weg.output }}")))), null);
        var gut = check.reviewRepair(original, new ArrayList<>(List.of(
                block("simple", null, Map.of("command", "Fasse zusammen")),
                block("shell", null, Map.of("command", "set -euo pipefail\necho ok")))), null);

        assertThat(fehlenderBlock.accepted()).isFalse();
        assertThat(fehlenderBlock.rejection()).isEqualTo("Struktur geändert: Blockzahl 2 → 1");
        assertThat(fehlenderBlock.blocks()).isSameAs(original);
        assertThat(schlechter.accepted()).isFalse();
        assertThat(schlechter.rejection()).startsWith("mehr HIGH-Befunde");
        assertThat(gut.accepted()).isTrue();
        assertThat(gut.blocks()).extracting(BlockDefinition::getName).containsExactly("zusammenfassung", "ablage");
        assertThat(gut.blocks().get(0).getSkillIds()).containsExactly("skill-1");
        assertThat(GeneratedWorkflowCheck.highCount(gut.findings())).isZero();
    }

    @Test
    void shapeChangeErkenntFehlendeVerschachtelteBloecke() {
        var switchBlock = block("switch", "weiche", Map.of("cases", List.of(Map.of("blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "a")),
                Map.of("type", "simple", "config", Map.of("command", "b")))))));
        var ohneZweiten = block("switch", "weiche", Map.of("cases", List.of(Map.of("blocks", List.of(
                Map.of("type", "shell", "config", Map.of("command", "a")))))));
        var gleichGeformt = block("switch", "weiche", Map.of("cases", List.of(Map.of("blocks", List.of(
                Map.of("type", "shell", "config", Map.of("command", "a")),
                Map.of("type", "simple", "config", Map.of("command", "b")))))));

        assertThat(CorrectionAnswer.shapeChange(List.of(switchBlock), List.of(ohneZweiten)))
                .isEqualTo("Block 1: verschachtelte Blöcke 2 → 1");
        assertThat(CorrectionAnswer.shapeChange(List.of(switchBlock), List.of(gleichGeformt))).isNull();
        assertThat(CorrectionAnswer.shapeChange(badAndHealthy(), badBlocks())).isEqualTo("Blockzahl 2 → 1");
    }

    @Test
    void reportRemainingSchreibtWarnAmWorkflow() {
        var findings = check.findings(badBlocks(), null);

        check.reportRemaining("wf-1", "Bericht", "Factory", findings);

        verify(activityLog).warn(eq(LogCategory.SYSTEM), contains("Baufehler-Prüfung (Factory): Workflow „Bericht“"),
                eq("GeneratedWorkflowCheck"), isNull(), eq("wf-1"));
    }

    @Test
    void reportRemainingOhneBefundeSchreibtNichts() {
        check.reportRemaining("wf-1", "Bericht", "Factory", List.of());

        verifyNoInteractions(activityLog);
    }

    @Test
    void reportRemainingSchlucktFehlerDesLogs() {
        when(activityLog.warn(any(), anyString(), anyString(), any(), anyString())).thenThrow(new IllegalStateException("db"));

        check.reportRemaining("wf-1", "Bericht", "Factory", check.findings(badBlocks(), null));
        // no throw: the check does not abort a save path
    }
}
