package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The key rules on the real cases from the dev instance's inventory (TODO f5153c47): none of them
 * was noticed on saving, each ran silently without the intended effect.
 */
class ConfigKeyRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    /** Only the findings of the three key rules — the other rules are not the subject here. */
    private List<AnalyzerFinding> keyFindings(BlockDefinition... blocks) {
        return analyzer.analyze(List.of(blocks), null).stream()
                .filter(f -> f.rule().endsWith("config-key") || f.rule().equals("invalid-config-value"))
                .toList();
    }

    @Nested
    class UnbekannteUndFehlendeSchluessel {

        @Test
        void agenticMitCommandStattGoal() {
            var findings = keyFindings(block("agentic", "umsetzen", Map.of("command", "Baue das Feature")));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                    .containsExactly(
                            tuple("missing-required-config-key", Severity.HIGH, 0),
                            tuple("unknown-config-key", Severity.MEDIUM, 0));
            assertThat(findings.get(0).detail()).contains("goal");
            assertThat(findings.get(1).detail()).contains("command").contains("agentic");
        }

        @Test
        void simpleMitPromptStattCommand() {
            var findings = keyFindings(block("simple", "frage", Map.of("prompt", "Fasse zusammen")));

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "unknown-config-key");
            assertThat(findings.get(1).detail()).contains("prompt");
        }

        @Test
        void subWorkflowMitWorkflowIdLookup() {
            var findings = keyFindings(block("sub-workflow", "unterlauf", Map.of("workflow_id_lookup", "Deploy")));

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "unknown-config-key");
            assertThat(findings.get(0).detail()).contains("workflow_id");
        }

        /**
         * The executor keeps reading the old spelling — it is neither unknown nor is workflow_id missing. It is being
         * phased out (5ee07e94): a LOW hint with the new name.
         */
        @Test
        void subWorkflowMitAlterSchreibweiseWorkflowIdIstNurEinHinweis() {
            var findings = keyFindings(block("sub-workflow", "alt", Map.of("workflowId", "abc")));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::severity, AnalyzerFinding::blockIndex)
                    .containsExactly(tuple("legacy-config-key", Severity.LOW, 0));
            assertThat(findings.get(0).detail())
                    .isEqualTo("workflowId ist die alte Schreibweise von workflow_id — sie wird noch gelesen, fällt aber "
                            + "später weg; den Schlüssel in workflow_id umbenennen");
            assertThat(findings.get(0).detailEn()).endsWith("rename the key to workflow_id");
        }

        /** If both are present, workflow_id applies; the hint says that the old key can go. */
        @Test
        void subWorkflowMitBeidenSchreibweisenRaetZumEntfernen() {
            var findings = keyFindings(block("sub-workflow", "beide", Map.of("workflow_id", "neu", "workflowId", "alt")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("legacy-config-key");
                assertThat(f.detail()).endsWith("workflow_id ist auch gesetzt und gilt, workflowId entfernen");
                assertThat(f.detailEn()).endsWith("workflow_id is set as well and wins, remove workflowId");
            });
        }

        /** Also nested, with path. */
        @Test
        void verschachtelterSubWorkflowMitAlterSchreibweise() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("type", "sub-workflow");
            inner.put("name", "kind");
            inner.put("config", Map.of("workflowId", "abc"));
            var findings = keyFindings(block("switch", "weiche", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))))));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("legacy-config-key");
                assertThat(f.detail()).startsWith("Block 1 › cases[0] › Block 1 „kind“: workflowId ist die alte");
            });
        }

        @Test
        void agenticMitInstructionsAddendum() {
            var findings = keyFindings(block("agentic", "agent",
                    Map.of("goal", "Räume auf", "instructions_addendum", "Sei gründlich")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("instructions_addendum");
            });
        }

        @Test
        void documentMitMaxChars() {
            var findings = keyFindings(block("document", "pdf", Map.of("file_path", "a.pdf", "max_chars", 4000)));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("max_chars").contains("document");
            });
        }

        @Test
        void nameInDerConfigGehoertAufDieBlockebene() {
            var findings = keyFindings(block("shell", null,
                    Map.of("command", "set -euo pipefail\nls", "name", "liste")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("name gehört neben config auf die Blockebene")
                        .doesNotContain("meintest du");
            });
        }

        @Test
        void naheSchreibweiseBekommtEinenVorschlag() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "timeout_second", 60))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("meintest du timeout_seconds?");
            assertThat(keyFindings(block("sub-workflow", "s", Map.of("workflow_id", "x", "Workflow-ID", "y"))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString()
                    .contains("meintest du workflow_id?");
        }

        @Test
        void fehlenderPflichtschluesselNenntDenAehnlichenDaneben() {
            var findings = keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "comand", "echo")));

            assertThat(findings.get(0).rule()).isEqualTo("missing-required-config-key");
            assertThat(findings.get(0).detail()).contains("gesetzt ist comand");
        }

        /** foreach runs with command or workflow_id (a5c9fbbf); context only takes effect with workflow_id. */
        @Test
        void foreachBrauchtGenauEinenVonCommandUndWorkflowId() {
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo")))).isEmpty();
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "workflow_id", "{{ wf }}",
                    "context", Map.of("x", "{{ item.id }}"))))).isEmpty();
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo",
                    "workflow_id", "wf")))).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("schließen sich aus"));
            assertThat(keyFindings(block("foreach", "f", Map.of("items", List.of("a"), "command", "echo",
                    "context", Map.of("x", "y"))))).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("context wirkt nur mit workflow_id"));
        }

        /** todo-update runs with id or updates – with neither or both it aborts (d6c0afe6). */
        @Test
        void todoUpdateBrauchtGenauEinenVonIdUndUpdates() {
            assertThat(keyFindings(block("todo-update", "u", Map.of("id", "{{ todo_id }}")))).isEmpty();
            assertThat(keyFindings(block("todo-update", "u", Map.of("updates", "{{ block.x.output }}")))).isEmpty();
            assertThat(keyFindings(block("todo-update", "u", Map.of("title", "Neu"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("missing-required-config-key");
                        assertThat(f.detail()).contains("id oder updates");
                    });
            // An empty text counts as not set, as in the executor
            assertThat(keyFindings(block("todo-update", "u", Map.of("id", "", "updates", "[]")))).isEmpty();
            assertThat(keyFindings(block("todo-update", "u", Map.of("id", " "))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString().contains("id oder updates");
            assertThat(keyFindings(block("todo-update", "u", Map.of("id", "a", "updates", "[]"))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("missing-required-config-key");
                        assertThat(f.detail()).contains("nur einer");
                    });
        }

        /** Tool mode exists only at top level: nested, the analyzer reports it, command stays mandatory. */
        @Test
        void werkzeugModusInVerschachteltenBloeckenWirdGemeldet() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("type", "shell");
            inner.put("name", "scan");
            inner.put("config", Map.of("container_image", "img:1", "container_args", List.of("fs")));
            var findings = keyFindings(block("switch", "weiche", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(inner))))));

            assertThat(findings).anySatisfy(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("container_args", "oberster Ebene");
            }).anySatisfy(f -> {
                assertThat(f.rule()).isEqualTo("missing-required-config-key");
                assertThat(f.detail()).contains("command");
            });
        }

        /** Shell runs with command or, in tool mode, with container_args – never with both (d3a7f5e2). */
        @Test
        void shellBrauchtGenauEinenVonCommandUndContainerArgs() {
            assertThat(keyFindings(block("shell", "s", Map.of("container_image", "img:1",
                    "container_args", List.of("fs"), "output_format", "sarif", "cache_dir", "/cache")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "echo",
                    "container_image", "img:1", "container_args", List.of("fs")))))
                    .singleElement().extracting(AnalyzerFinding::rule).isEqualTo("missing-required-config-key");
        }

        @Test
        void kurzeSchluesselBekommenKeinenGeratenenVorschlag() {
            assertThat(ConfigKeyRules.suggestion("mode", java.util.Set.of("node", "name"))).isEqualTo("node");
            assertThat(ConfigKeyRules.suggestion("ab", java.util.Set.of("id"))).isNull();
            assertThat(ConfigKeyRules.suggestion("prompt", java.util.Set.of("command", "provider"))).isNull();
        }

        /** retry, foreach, while, conditional pass container_image on to the shell run only in shell mode. */
        @Test
        void retryMitContainerImageWirktNurImShellModus() {
            var ai = keyFindings(block("retry", "r", Map.of("command", "npm test", "container_image", "node:24")));
            var shell = keyFindings(block("retry", "r",
                    Map.of("command", "npm test", "container_image", "node:24", "execution_mode", "shell")));

            assertThat(ai).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("unknown-config-key");
                assertThat(f.detail()).contains("container_image wirkt nur mit execution_mode: shell");
            });
            assertThat(shell).isEmpty();
        }

        /** RunIfGate quotes the placeholders of the run_if condition with shell_escape_variables — for every block type. */
        @Test
        void shellEscapeVariablesWirktBeiJedemBlockMitRunIf() {
            assertThat(keyFindings(block("simple", "s", Map.of("command", "Fasse zusammen",
                    "run_if", "test -n {{ ziel }}", "shell_escape_variables", true)))).isEmpty();
            assertThat(keyFindings(block("simple", "s", Map.of("command", "Fasse zusammen",
                    "shell_escape_variables", true))))
                    .singleElement().satisfies(f -> {
                        assertThat(f.rule()).isEqualTo("unknown-config-key");
                        assertThat(f.detail()).contains("shell_escape_variables wirkt nur mit run_if");
                    });
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "shell_escape_variables", true))))
                    .as("Shell-Blöcke lesen den Schlüssel selbst").isEmpty();
        }

        @Test
        void gemeinsameKiUndValidierungsschluesselSindBekannt() {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("command", "Fasse zusammen");
            config.put("label", "Zusammenfassung");
            config.put("run_if", "test -f a");
            config.put("provider", "claude");
            config.put("effort", "HIGH");
            config.put("validation", Map.of("contains", "OK"));
            config.put("on_failure", "continue");

            assertThat(keyFindings(block("simple", "s", config))).isEmpty();
        }

        @Test
        void kiSchluesselNurBeiKiBloecken() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "model", "opus"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("unknown-config-key");
        }

        @Test
        void blocktypOhneSchemaBleibtUngeprueft() {
            assertThat(keyFindings(block("future-block", "x", Map.of("whatever", 1, "on_failure", "fail")))).isEmpty();
            assertThat(keyFindings(block(null, "x", Map.of("whatever", 1)))).isEmpty();
        }

        @Test
        void blockOhneConfigMeldetNurFehlendePflichtschluessel() {
            assertThat(keyFindings(block("switch", "s", null))).extracting(AnalyzerFinding::rule)
                    .containsExactly("missing-required-config-key", "missing-required-config-key");
        }
    }

    @Nested
    class Werte {

        @Test
        void onFailureFailIstEinSchwererBefund() {
            var findings = keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "fail")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("invalid-config-value");
                assertThat(f.severity()).isEqualTo(Severity.HIGH);
                assertThat(f.detail()).contains("\"fail\"").contains("erlaubt: stop, continue")
                        .as("die Laufzeitwirkung ändert sich gerade (2b361abe) — die Meldung behauptet sie nicht")
                        .doesNotContain("läuft weiter").doesNotContain("stoppt");
            });
        }

        @Test
        void andereUngueltigeWerteSindMittel() {
            var findings = keyFindings(block("approval", "a", Map.of("on_no", "abort")));

            assertThat(findings).singleElement().satisfies(f -> {
                assertThat(f.rule()).isEqualTo("invalid-config-value");
                assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            });
        }

        @Test
        void platzhalterUndLegacyWerteGeltenAlsGueltig() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "{{ policy }}")))).isEmpty();
            assertThat(keyFindings(block("foreach", "f",
                    Map.of("command", "x", "items", List.of("a"), "execution_mode", "claude")))).isEmpty();
        }

        @Test
        void grossKleinschreibungZaehltNurWoDerExecutorSieBeachtet() {
            assertThat(keyFindings(block("todo-create", "t", Map.of("title", "x", "priority", "high")))).isEmpty();
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", "Continue"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("invalid-config-value");
        }

        @Test
        void nichtTextwerteBeiFestenWertenSindUngueltig() {
            assertThat(keyFindings(block("shell", "s", Map.of("command", "ls", "on_failure", false))))
                    .singleElement().extracting(AnalyzerFinding::detail).asString().contains("on_failure: false");
        }

        /** The wrong items_source next to items_command is reported by items-command-without-source — not twice. */
        @Test
        void foreachItemsSourceNebenItemsCommandMeldetNurDieForEachRegel() {
            var findings = analyzer.analyze(List.of(block("foreach", "f",
                    Map.of("command", "x", "items_command", "ls", "items_source", "cmd"))), null);

            assertThat(findings).extracting(AnalyzerFinding::rule)
                    .contains("items-command-without-source").doesNotContain("invalid-config-value");
            assertThat(keyFindings(block("foreach", "f", Map.of("command", "x", "items_source", "cmd"))))
                    .extracting(AnalyzerFinding::rule).containsExactly("invalid-config-value");
        }
    }

    @Nested
    class Verschachtelt {

        @Test
        void blockInCasesNenntSeinenPfadUndHaengtAmAeusserenBlock() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("type", "simple");
            nested.put("name", "bericht");
            nested.put("config", Map.of("prompt", "Schreib den Bericht", "on_failure", "fail"));
            var findings = keyFindings(
                    block("shell", "vorher", Map.of("command", "echo GREEN")),
                    block("switch", "weiche", Map.of("expression", "{{ previous_output }}",
                            "cases", List.of(Map.of("value", "GREEN", "blocks", List.of(nested))))));

            assertThat(findings).extracting(AnalyzerFinding::rule, AnalyzerFinding::blockIndex, AnalyzerFinding::blockName)
                    .containsExactlyInAnyOrder(
                            tuple("missing-required-config-key", 1, "weiche"),
                            tuple("invalid-config-value", 1, "weiche"),
                            tuple("unknown-config-key", 1, "weiche"));
            assertThat(findings).allSatisfy(f ->
                    assertThat(f.detail()).startsWith("Block 2 › cases[0] › Block 1 „bericht“: "));
        }

        @Test
        void korrekterVerschachtelterBlockIstKeinBefund() {
            var findings = keyFindings(block("switch", "weiche", Map.of("expression", "x",
                    "cases", List.of(Map.of("value", "a", "blocks", List.of(
                            Map.of("type", "shell", "config", Map.of("command", "ls", "timeout_seconds", 30))))),
                    "default", List.of(Map.of("type", "simple", "config", Map.of("command", "Hallo"))))));

            assertThat(findings).isEmpty();
        }
    }
}
