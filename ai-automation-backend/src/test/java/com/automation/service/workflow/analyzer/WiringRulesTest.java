package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Wiring rules directly, without sorting/hygiene of the WorkflowAnalyzer. */
class WiringRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(BlockDefinition... blocks) {
        return apply(Map.of(), blocks);
    }

    private static List<AnalyzerFinding> apply(Map<String, Object> metadata, BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(blocks), metadata, out);
        return out;
    }

    @Test
    void leereListeLiefertNichts() {
        List<AnalyzerFinding> out = new ArrayList<>();
        WiringRules.apply(List.of(), Map.of(), out);
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

    // --- audit-only-placeholder (TODO 5ae9bd32) --------------------------------------------

    private static final Map<String, Object> AUDIT_META = Map.of("audit", Map.of(
            "enabled", true, "key", "project.security", "category", "security", "reportBlock", "bericht"));

    @Test
    void auditBefundeOhneAuditMetadatenSindEinBefundJeBlock() {
        List<AnalyzerFinding> out = apply(
                block("shell", "sammeln", Map.of("command", "set -euo pipefail\necho {{ audit_findings }}")),
                block("simple", "bericht", Map.of("command", "Bekannt: {{ audit_findings }} und {{audit_findings}}")));

        assertThat(out).filteredOn(f -> f.rule().equals("audit-only-placeholder")).hasSize(2).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.detail()).contains("{{ audit_findings }}").contains("metadata.audit");
        }).extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::blockName)
                .containsExactly(tuple(0, "sammeln"), tuple(1, "bericht"));
    }

    @Test
    void auditBefundeMitGueltigemAuditBlockSindKeinBefund() {
        assertThat(apply(AUDIT_META, block("simple", "bericht", Map.of("command", "Bekannt: {{ audit_findings }}"))))
                .extracting(AnalyzerFinding::rule).doesNotContain("audit-only-placeholder");
    }

    /** Decided as at runtime: a switched-off or incomplete audit block does not bring the variable. */
    @Test
    void auditBefundeMitAbgeschaltetemOderUnvollstaendigemAuditBlockSindEinBefund() {
        Map<String, Object> disabled = Map.of("audit", Map.of(
                "enabled", false, "key", "project.security", "category", "security", "reportBlock", "bericht"));
        Map<String, Object> withoutReportBlock = Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security"));
        BlockDefinition report = block("simple", "bericht", Map.of("command", "Bekannt: {{ audit_findings }}"));

        assertThat(apply(disabled, report)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
        assertThat(apply(withoutReportBlock, report)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
    }

    @Test
    void auditBefundeImVerschachteltenBlockZaehlen_einAndererPlatzhalterNicht() {
        List<AnalyzerFinding> out = apply(block("foreach", "je Befund", Map.of(
                "blocks", List.of(Map.of("config", Map.of("command", "{{ audit_findings }}"))),
                "note", "{{ audit_findings_count }} {{ open_todos }}")));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("audit-only-placeholder");
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
