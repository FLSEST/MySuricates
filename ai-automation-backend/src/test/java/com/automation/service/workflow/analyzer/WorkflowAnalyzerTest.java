package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Jede Regel einmal ausgeloest und einmal bewusst nicht (TODO 182bd224). */
class WorkflowAnalyzerTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private List<String> rules(List<BlockDefinition> blocks, String instructions) {
        return analyzer.analyze(blocks, instructions).stream().map(AnalyzerFinding::rule).toList();
    }

    @Test
    void verweisAufUnbekanntenBlockIstEinBefund() {
        var blocks = List.of(
                block("shell", "messung", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("prompt", "Lies {{ block.messsung.output }}")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unknown-block-reference");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detail()).contains("messsung");
        });
    }

    @Test
    void richtigGeschriebenerVerweisIstKeinBefund() {
        var blocks = List.of(
                block("shell", "messung", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("prompt", "Lies {{ block.messung.output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unknown-block-reference", "unwired-output");
    }

    @Test
    void chainVariableInEinemEinzelnenWorkflowIstEinBefund() {
        var blocks = List.of(
                block("simple", "a", Map.of("prompt", "Nimm {{ previous_chain_output }} und fasse zusammen")));

        assertThat(rules(blocks, null)).contains("chain-only-placeholder");
    }

    @Test
    void ungeleseneKiAusgabeIstEinBefund() {
        var blocks = List.of(
                block("simple", "analyse", Map.of("prompt", "Analysiere das Projekt ausfuehrlich")),
                block("simple", "report", Map.of("prompt", "Schreibe einen Bericht ueber das Projekt")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
        });
    }

    /** Step-Indizes sind 0-basiert: {@code step_0_output} ist der erste Block (TODO fb4f2ccf). */
    @Test
    void ausgabeUeberNullBasiertenStepIndexGiltAlsGelesen() {
        var blocks = List.of(
                block("simple", "analyse", Map.of("prompt", "Analysiere das Projekt ausfuehrlich")),
                block("shell", "zwischen", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("prompt", "Schreibe einen Bericht aus {{ step_0_output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void verweisAufDenFolgendenStepIndexLiestDenBlockNicht() {
        var blocks = List.of(
                block("simple", "analyse", Map.of("prompt", "Analysiere das Projekt ausfuehrlich")),
                block("shell", "zwischen", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("prompt", "Schreibe einen Bericht aus {{ step_1_output }}")));

        assertThat(analyzer.analyze(blocks, null)).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("unwired-output");
            assertThat(f.blockIndex()).isZero();
        });
    }

    /**
     * Ein Shell-Block, dessen Ausgabe niemand ueber einen Platzhalter liest, schreibt meist eine
     * Datei, die der naechste Block wieder einliest — das ist legitim und darf nicht melden.
     */
    @Test
    void ungeleseneShellAusgabeIstKeinBefund() {
        var blocks = List.of(
                block("shell", "messung", Map.of("command", "set -euo pipefail\nls > /tmp/liste.txt")),
                block("shell", "auswertung", Map.of("command", "set -euo pipefail\nwc -l /tmp/liste.txt")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void letzterBlockUndWrapperLoesenKeineToteAusgabeAus() {
        var blocks = List.of(
                block("foreach", "je Datei", Map.of("items_command", "ls", "command", "tu etwas")),
                block("simple", "ende", Map.of("prompt", "Fasse zusammen")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void pfadMitKlarnamenIstEinBefund() {
        var blocks = List.of(block("shell", "bau", Map.of("command",
                "set -euo pipefail\ncd /home/erika/projekt && mvn test")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("absolute-home-path");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detail()).contains("erika");
        });
    }

    @Test
    void shellOhneFehlerabbruchIstEinBefund() {
        var ohne = List.of(block("shell", "bau", Map.of("command", "mvn test")));
        var mit = List.of(block("shell", "bau", Map.of("command", "set -euo pipefail\nmvn test")));

        assertThat(rules(ohne, null)).contains("missing-strict-mode");
        assertThat(rules(mit, null)).doesNotContain("missing-strict-mode");
    }

    @Test
    void woertlichWiederholteAnweisungIstEinBefund() {
        String instructions = "Arbeite ausschliesslich mit dem Faktenblatt und zaehle nichts selbst. "
                + "Jede Zahl im Bericht nennt die FAKT-Zeile, auf der sie beruht, sonst gehoert sie nicht hinein.";
        var blocks = List.of(block("simple", "report", Map.of("prompt", "Schreibe den Bericht.\n\n" + instructions)));

        assertThat(rules(blocks, instructions)).contains("duplicated-instructions");
    }

    @Test
    void datierteModellIdIstEinBefund() {
        var blocks = List.of(block("simple", "a", Map.of(
                "prompt", "Fasse zusammen", "model", "claude-opus-4-6")));

        assertThat(rules(blocks, null)).contains("dated-model-id");
        assertThat(rules(List.of(block("simple", "a", Map.of("prompt", "Fasse zusammen", "model", "opus"))), null))
                .doesNotContain("dated-model-id");
    }

    @Test
    void verschachtelterBlockWirdMitgeprueft() {
        var blocks = List.of(block("foreach", "je Datei", Map.of(
                "items_command", "ls",
                "blocks", List.of(Map.of("type", "shell", "config", Map.of("command", "cd /home/erika/x"))))));

        assertThat(rules(blocks, null)).contains("absolute-home-path");
    }

    @Test
    void sauberDefinierterWorkflowLiefertKeineBefunde() {
        var blocks = List.of(
                block("shell", "messung", Map.of("command",
                        "set -euo pipefail\ncd {{ project_path }}\nls docs")),
                block("simple", "report", Map.of("prompt",
                        "Werte {{ block.messung.output }} aus und fasse zusammen.",
                        "model", "opus")));

        assertThat(analyzer.analyze(blocks, null)).isEmpty();
    }

    @Test
    void leereDefinitionLiefertNichts() {
        assertThat(analyzer.analyze(List.of(), null)).isEmpty();
        assertThat(analyzer.analyze((java.util.List<BlockDefinition>) null, null)).isEmpty();
    }

    @Test
    void befundeStehenSchwersteZuerst() {
        var blocks = List.of(
                block("shell", "a", Map.of("command", "mvn test")),
                block("simple", "b", Map.of("prompt", "Nimm {{ block.fehlt.output }}")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).isNotEmpty();
        assertThat(findings.getFirst().severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
    }

    // --- TODO 41aeeb09: on_no ohne Option, die ablehnt ---

    @Test
    void onNoOhneAblehnendeOptionIstEinBefund() {
        var blocks = List.of(block("approval", "freigabe", Map.of(
                "question", "Weiter?", "options", List.of("Weiter", "Abbrechen"), "on_no", "fail")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detail()).contains("Abbrechen");
        });
    }

    @Test
    void onNoMitNeinOderRejectOptionsIstKeinBefund() {
        var blocks = List.of(
                block("approval", "deutsch", Map.of("options", List.of("Ja", "Nein"), "on_no", "fail")),
                block("approval", "eigene", Map.of("options", List.of("Weiter", "Abbrechen"),
                        "reject_options", List.of("Abbrechen"), "on_no", "skip")),
                block("approval", "vorgabe", Map.of("on_no", "fail")),
                block("approval", "ohne-on-no", Map.of("options", List.of("A", "B"))));

        assertThat(rules(blocks, null)).doesNotContain("on-no-without-reject-option");
    }

    @Test
    void verschachteltesApprovalWirdMitgeprueft() {
        var inner = Map.<String, Object>of("type", "approval", "name", "je-datei",
                "config", Map.of("options", List.of("OK", "Stopp"), "on_no", "fail"));
        var blocks = List.of(block("foreach", "dateien", Map.of("items", "a,b", "blocks", List.of(inner))));

        assertThat(analyzer.analyze(blocks, null)).anySatisfy(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.blockIndex()).isZero();
            assertThat(f.blockName()).isEqualTo("dateien › je-datei");
        });
    }
}
