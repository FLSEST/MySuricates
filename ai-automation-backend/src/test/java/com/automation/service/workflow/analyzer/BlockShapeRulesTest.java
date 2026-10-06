package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What sits on a block but never runs: {@code config.blocks} (5536c5aa) and fields next to {@code config}
 * (d3aa0b28) — neither was noticed on saving, the run silently did something else.
 */
class BlockShapeRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private List<AnalyzerFinding> shapeFindings(List<BlockDefinition> blocks) {
        return analyzer.analyze(blocks, null).stream()
                .filter(f -> f.rule().equals("dead-nested-blocks") || f.rule().equals("unknown-block-field")
                        || f.rule().equals("unknown-config-key"))
                .toList();
    }

    @Test
    void foreachMitBlocksListe_istHigh_undNichtNochmalAlsUnbekannterSchluessel() {
        var findings = shapeFindings(List.of(block("foreach", "features", Map.of(
                "items", List.of("a", "b"), "command", "echo {{ item }}",
                "blocks", List.of(Map.of("type", "sub-workflow", "config", Map.of("workflow_id", "x")))))));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                .containsExactly(tuple("dead-nested-blocks", Severity.HIGH, 0));
        assertThat(findings.get(0).detail()).contains("config.blocks (foreach) mit 1 Block-Einträgen läuft nie");
    }

    @Test
    void switchMitCasesUndDefault_istInOrdnung_nurEineBlocksListeDanebenNicht() {
        Map<String, Object> inner = Map.of("type", "shell", "config", Map.of("command", "set -euo pipefail\nls"));
        assertThat(shapeFindings(List.of(block("switch", "weiche", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))), "default", List.of(inner))))))
                .isEmpty();

        assertThat(shapeFindings(List.of(block("switch", "weiche", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))), "blocks", List.of(inner))))))
                .extracting(AnalyzerFinding::rule).containsExactly("dead-nested-blocks");
    }

    @Test
    void blocksListeInEinemVerschachteltenBlock_nenntDenPfad() {
        Map<String, Object> retry = Map.of("type", "retry", "name", "nochmal",
                "config", Map.of("command", "npm test", "blocks", List.of()));
        var findings = shapeFindings(List.of(block("switch", "weiche", Map.of("expression", "x",
                "default", List.of(retry)))));

        assertThat(findings).extracting(AnalyzerFinding::rule).containsExactly("dead-nested-blocks");
        assertThat(findings.get(0).detail()).startsWith("Block 1 › default › Block 1 „nochmal“: config.blocks (retry)");
    }

    @Test
    void onFailureNebenConfig_istHigh_wieImWalkthroughWorkflow() throws Exception {
        // this is how it stood in walkthrough-illustrationen.json (block „sichtpruefung“) before bf71f6b1 moved it
        List<BlockDefinition> blocks = new ObjectMapper().readValue("""
                [{"type":"shell","name":"sichtpruefung","config":{"command":"set -euo pipefail\\nls"},
                  "on_failure":"continue"}]
                """, new TypeReference<>() {});

        var findings = shapeFindings(blocks);

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("unknown-block-field", Severity.HIGH));
        assertThat(findings.get(0).detail()).startsWith("on_failure steht neben config statt darin");
        assertThat(blocks.get(0).shouldStopOnFailure()).isTrue();
    }

    @Test
    void naheSchreibweiseEinesBlockfelds_istMedium_sonstLow() {
        BlockDefinition b = block("simple", "frage", Map.of("command", "Fasse zusammen"));
        b.putUnknownField("skill_ids", List.of("s1"));
        b.putUnknownField("kommentar", "nur eine Notiz");

        assertThat(shapeFindings(List.of(b)))
                .extracting(AnalyzerFinding::severity, f -> f.detail().contains("skillIds"))
                .containsExactlyInAnyOrder(tuple(Severity.MEDIUM, true), tuple(Severity.LOW, true));
    }

    @Test
    void verschachtelterBlockMitFeldNebenConfig_wirdGemeldet() {
        Map<String, Object> stray = new HashMap<>();
        stray.put("type", "shell");
        stray.put("name", "aufraeumen");
        stray.put("config", Map.of("command", "set -euo pipefail\nrm -f x"));
        stray.put("on_failure", "continue");

        var findings = shapeFindings(List.of(block("switch", "weiche", Map.of("expression", "x",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(stray)))))));

        assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity)
                .containsExactly(tuple("unknown-block-field", Severity.HIGH));
        assertThat(findings.get(0).detailEn())
                .startsWith("Block 1 › cases[0] › Block 1 \"aufraeumen\": on_failure sits next to config");
    }

    @Test
    void wohlgeformterBlock_hatKeinenBefund() {
        BlockDefinition b = block("simple", "frage", Map.of("command", "Fasse zusammen"));
        b.setSkillIds(List.of("s1"));
        b.setRequiredCapabilities(List.of("git"));

        assertThat(shapeFindings(List.of(b))).isEmpty();
    }
}
