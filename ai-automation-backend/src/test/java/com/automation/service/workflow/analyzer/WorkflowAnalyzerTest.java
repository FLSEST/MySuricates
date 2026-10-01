package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
                block("simple", "report", Map.of("command", "Lies {{ block.messsung.output }}")));

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
                block("simple", "report", Map.of("command", "Lies {{ block.messung.output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unknown-block-reference", "unwired-output");
    }

    @Test
    void chainVariableInEinemEinzelnenWorkflowIstEinBefund() {
        var blocks = List.of(
                block("simple", "a", Map.of("command", "Nimm {{ previous_chain_output }} und fasse zusammen")));

        assertThat(rules(blocks, null)).contains("chain-only-placeholder");
    }

    /**
     * TODO 6db47bc2: gemeldet wird nur, was eine Chain tatsächlich setzt. {@code chain_step_output} setzt
     * niemand — die Meldung „nur innerhalb einer Chain belegt“ hätte dort in die Irre geführt.
     */
    @Test
    void nurVonDerChainGesetzteVariablenSindChainVariablen() {
        var runId = List.of(block("simple", "a", Map.of("command", "Chain-Run {{ chain_run_id }}")));
        var stepOutput = List.of(block("simple", "a", Map.of("command", "Nimm {{ chain_step_output }}")));

        assertThat(rules(runId, null)).contains("chain-only-placeholder");
        assertThat(rules(stepOutput, null)).doesNotContain("chain-only-placeholder");
        assertThat(WiringRules.CHAIN_ONLY).containsExactlyInAnyOrder("previous_chain_output", "chain_run_id");
    }

    /** TODO 5ae9bd32: {@code audit_findings} gibt es nur in einem Audit-Workflow – die Metadaten entscheiden. */
    @Test
    void auditBefundeOhneAuditWorkflowSindEinBefund_mitGueltigemAuditBlockNicht() {
        var blocks = List.of(block("simple", "bericht", Map.of("command", "Bekannte Befunde: {{ audit_findings }}")));
        Map<String, Object> audit = Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security", "reportBlock", "bericht"));

        assertThat(rules(blocks, null)).contains("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, Map.of("autoTodo", Map.of("enabled", true))))
                .extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, audit)).extracting(AnalyzerFinding::rule)
                .doesNotContain("audit-only-placeholder");
        assertThat(analyzer.analyze(blocks, null, null)).extracting(AnalyzerFinding::rule)
                .as("ohne Metadaten: kein Audit-Workflow").contains("audit-only-placeholder");
    }

    /** Der REST-Weg analysiert die Entity: deren Metadaten muessen ankommen, sonst meldete er jeden Audit-Workflow. */
    @Test
    void analyseDerEntityLiestDerenMetadaten() {
        var blocks = List.of(block("simple", "bericht", Map.of("command", "Bekannte Befunde: {{ audit_findings }}")));
        Workflow audit = Workflow.builder().blocks(blocks).metadata(Map.of("audit", Map.of(
                "enabled", true, "key", "project.security", "category", "security", "reportBlock", "bericht"))).build();
        Workflow plain = Workflow.builder().blocks(blocks).build();

        assertThat(analyzer.analyze(audit)).extracting(AnalyzerFinding::rule).doesNotContain("audit-only-placeholder");
        assertThat(analyzer.analyze(plain)).extracting(AnalyzerFinding::rule).contains("audit-only-placeholder");
    }

    @Test
    void ungeleseneKiAusgabeIstEinBefund() {
        var blocks = List.of(
                block("simple", "analyse", Map.of("command", "Analysiere das Projekt ausfuehrlich")),
                block("simple", "report", Map.of("command", "Schreibe einen Bericht ueber das Projekt")));

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
                block("simple", "analyse", Map.of("command", "Analysiere das Projekt ausfuehrlich")),
                block("shell", "zwischen", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Schreibe einen Bericht aus {{ step_0_output }}")));

        assertThat(rules(blocks, null)).doesNotContain("unwired-output");
    }

    @Test
    void verweisAufDenFolgendenStepIndexLiestDenBlockNicht() {
        var blocks = List.of(
                block("simple", "analyse", Map.of("command", "Analysiere das Projekt ausfuehrlich")),
                block("shell", "zwischen", Map.of("command", "set -euo pipefail\necho ok")),
                block("simple", "report", Map.of("command", "Schreibe einen Bericht aus {{ step_1_output }}")));

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
                block("simple", "ende", Map.of("command", "Fasse zusammen")));

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
        var blocks = List.of(block("simple", "report", Map.of("command", "Schreibe den Bericht.\n\n" + instructions)));

        assertThat(rules(blocks, instructions)).contains("duplicated-instructions");
    }

    @Test
    void datierteModellIdIstEinBefund() {
        var blocks = List.of(block("simple", "a", Map.of(
                "command", "Fasse zusammen", "model", "claude-opus-4-6")));

        assertThat(rules(blocks, null)).contains("dated-model-id");
        assertThat(rules(List.of(block("simple", "a", Map.of("command", "Fasse zusammen", "model", "opus"))), null))
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
                block("simple", "report", Map.of("command",
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
                block("simple", "b", Map.of("command", "Nimm {{ block.fehlt.output }}")));

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

    // --- TODO 2f01ab01: items_command ohne items_source "command" läuft nie ---

    @Test
    void itemsCommandOhneItemsSourceIstEinBefund() {
        var blocks = List.of(
                block("foreach", "editor-standard", Map.of("items_command", "ls docs", "command", "wc -l {{ item }}")),
                block("foreach", "mit-liste", Map.of("items_source", "list", "items", List.of("a"),
                        "items_command", "ls docs", "command", "echo {{ item }}")));

        var findings = analyzer.analyze(blocks, null);

        assertThat(findings).filteredOn(f -> f.rule().equals("items-command-without-source"))
                .extracting(AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                .containsExactly(tuple(0, AnalyzerFinding.Severity.HIGH), tuple(1, AnalyzerFinding.Severity.MEDIUM));
        assertThat(findings.getFirst().detail()).contains("nicht gesetzt").contains("null Items");
    }

    @Test
    void itemsCommandMitItemsSourceCommandOderLeerIstKeinBefund() {
        var blocks = List.of(
                block("foreach", "richtig", Map.of("items_source", "command", "items_command", "ls docs",
                        "command", "echo {{ item }}")),
                block("foreach", "unausgefuellt", Map.of("items_command", "", "command", "")),
                block("foreach", "liste", Map.of("items", List.of("a", "b"), "command", "echo {{ item }}")));

        assertThat(rules(blocks, null)).doesNotContain("items-command-without-source");
    }

    // --- TODO d0348479: Live-Definitionen rufen die eigene API/DB nicht auf ---

    @Test
    void eigeneApiUndPlattformDbSindSchwereBefunde() {
        var blocks = List.of(
                block("shell", "todos", Map.of("command",
                        "set -euo pipefail\ncurl -s http://localhost:9090/api/v1/todos > todos.json")),
                block("shell", "key", Map.of("command",
                        "set -euo pipefail\nsqlite3 data/automation.db 'select value from app_settings'")));

        assertThat(analyzer.analyze(blocks, null))
                .filteredOn(f -> f.rule().equals("self-api-call") || f.rule().equals("direct-db-access"))
                .extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex, AnalyzerFinding::severity)
                .containsExactly(tuple("self-api-call", 0, AnalyzerFinding.Severity.HIGH),
                        tuple("direct-db-access", 1, AnalyzerFinding.Severity.HIGH));
    }

    @Test
    void healthSondeUndKontextvariableSindKeinZugriffAufDieEigenePlattform() {
        var blocks = List.of(
                block("shell", "probe", Map.of("command",
                        "set -euo pipefail\ncurl -sf http://localhost:9090/actuator/health\n"
                                + "curl -s http://localhost:9090/api/v1/system/jvm-memory")),
                block("simple", "report", Map.of("command", "Offene TODOs: {{ open_todos }}")));

        assertThat(rules(blocks, null)).doesNotContain("self-api-call", "direct-db-access");
    }
}
