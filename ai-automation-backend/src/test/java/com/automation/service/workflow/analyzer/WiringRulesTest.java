package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verdrahtungsregeln direkt, ohne Sortierung/Hygiene des WorkflowAnalyzer. */
class WiringRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void leereListeLiefertNichts() {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(), out);
        assertThat(out).isEmpty();
    }

    @Test
    void unbekannterBlockverweisNenntIndexNameUndVerweis() {
        List<AnalyzerFinding> out = apply(
                block("shell", "a", Map.of("command", "echo")),
                block("shell", "b", Map.of("command", "cat {{ block.x.output }} {{ block.a.output }}")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unknown-block-reference");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("b");
            assertThat(f.detail()).contains("{{ block.x.output }}").contains("\"x\"");
        });
    }

    @Test
    void verweisInVerschachtelterConfigZaehltUndAndereBlockPlatzhalterNicht() {
        List<AnalyzerFinding> out = apply(block("foreach", "loop", Map.of(
                "blocks", List.of(Map.of("config", Map.of("command", "{{ block.fehlt.output }}"))),
                "note", "{{ block.fehlt.status }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("unknown-block-reference");
    }

    @Test
    void chainVariableWirdJeBlockNurEinmalGemeldet() {
        List<AnalyzerFinding> out = apply(block("shell", null, Map.of(
                "command", "echo {{ chain_run_id }} {{ chain_run_id }} {{ previous_chain_output }}")));

        assertThat(out).extracting(AnalyzerFinding::rule)
                .containsExactly("chain-only-placeholder", "chain-only-placeholder");
        assertThat(out).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.blockName()).isNull();
        });
        assertThat(out).extracting(AnalyzerFinding::detail)
                .anyMatch(d -> d.contains("chain_run_id"))
                .anyMatch(d -> d.contains("previous_chain_output"));
    }

    @Test
    void ungeleseneKiAusgabeNenntBeideLesewegeImDetail() {
        List<AnalyzerFinding> out = apply(
                block("agentic", "plan", Map.of("goal", "Plane")),
                block("shell", "x", Map.of("command", "echo")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
            assertThat(f.detail()).contains("block.plan.output");
        });
    }

    @Test
    void unbenannterKiBlockNenntKeinenNamensweg() {
        List<AnalyzerFinding> out = apply(
                block("simple", "  ", Map.of("command", "a")),
                block("shell", "x", Map.of("command", "echo")));

        assertThat(out).singleElement().extracting(AnalyzerFinding::detail)
                .asString().doesNotContain("block.");
    }

    @Test
    void previousOutputImDirektFolgendenBlockGiltAlsGelesen() {
        assertThat(apply(
                block("simple", "a", Map.of("command", "x")),
                block("shell", "b", Map.of("command", "echo {{ previous_output }}")))).isEmpty();
    }

    @Test
    void namensverweisInEinemSpaeterenBlockGiltAlsGelesen() {
        assertThat(apply(
                block("skill-capability", "a", Map.of("capability", "analyze", "input", "x")),
                block("shell", "b", Map.of("command", "echo")),
                block("shell", "c", Map.of("command", "echo {{ block.a.output }}")))).isEmpty();
    }

    @Test
    void previousOutputWeiterHintenZaehltNichtFuerDenVorvorgaenger() {
        List<AnalyzerFinding> out = apply(
                block("compress-ai", "a", Map.of("source", "previous")),
                block("shell", "b", Map.of("command", "echo")),
                block("shell", "c", Map.of("command", "echo {{ previous_output }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("unwired-output");
    }

    @Test
    void nichtKiBloeckeUndBloeckeOhneTypWerdenNichtGeprueft() {
        assertThat(apply(
                block(null, "a", Map.of()),
                block("shell", "b", Map.of("command", "echo")),
                block("approval", "c", Map.of("question", "ok?")),
                block("shell", "d", Map.of("command", "echo")))).isEmpty();
    }

    @Test
    void wrapperMitKiInnenlebenWirdNichtGeprueft() {
        assertThat(apply(
                block("retry", "r", Map.of("block", Map.of("type", "simple"))),
                block("sub-workflow", "s", Map.of()),
                block("shell", "t", Map.of("command", "echo")))).isEmpty();
    }

    @Test
    void blockOhneConfigIstKeinFehler() {
        assertThat(apply(
                block("shell", "a", null),
                block("shell", "b", null))).isEmpty();
    }
}
