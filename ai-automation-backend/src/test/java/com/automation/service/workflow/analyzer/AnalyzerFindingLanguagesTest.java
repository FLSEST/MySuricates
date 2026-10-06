package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every analyzer rule delivers its finding bilingually (TODO 81773d7b): {@code detail} German, {@code detailEn}
 * English. The test triggers every rule — including the variants with their own text module (suggestion, block level,
 * "wirkt nur mit", nested path) — and checks both texts. Which rules exist it reads from the sources
 * ({@code AnalyzerFinding.of("…"}); a new rule without a fixture here turns it red.
 */
class AnalyzerFindingLanguagesTest {

    private static final Path RULES_DIR = Path.of("src/main/java/com/automation/service/workflow/analyzer");
    private static final Pattern RULE_KEY = Pattern.compile("AnalyzerFinding\\.of\\(\\s*\"([a-z-]+)\"");
    /** German in the English text: umlauts, German quotation marks, common function words. */
    private static final Pattern GERMAN = Pattern.compile("[äöüÄÖÜß„“]|\\b(der|die|das|nicht|und|wird|kein|keine|ist)\\b");

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static final String PARAGRAPH = "Always keep the answer short, name the file and the line, and never "
            + "invent a finding that the log does not show.";

    /** Fixtures, together every rule and every text module at least once. */
    private List<AnalyzerFinding> allFindings() {
        List<AnalyzerFinding> out = new ArrayList<>();
        // Verdrahtung: unknown-block-reference, chain-only-placeholder, audit-only-placeholder, unwired-output
        out.addAll(analyzer.analyze(List.of(
                block("simple", "first", Map.of("command", "Summarise {{ block.nope.output }} {{ previous_chain_output }}"
                        + " {{ audit_findings }}")),
                block("simple", "second", Map.of("command", "Write a report")),
                block("shell", "last", Map.of("command", "set -euo pipefail\necho done"))), null));
        // Hygiene: absolute-home-path, missing-strict-mode, duplicated-instructions (auch verschachtelt), dated-model-id
        out.addAll(analyzer.analyze(List.of(
                block("shell", "home", Map.of("command", "ls /home/alice/projects")),
                block("simple", "dup", Map.of("command", "Check it. " + PARAGRAPH, "model", "claude-opus-4")),
                block("switch", "branch", Map.of("expression", "x", "cases", List.of(Map.of("value", "a", "blocks",
                        List.of(Map.of("type", "simple", "name", "inner", "config", Map.of("command", PARAGRAPH)))))))),
                "Intro\n\n" + PARAGRAPH));
        // Approval, foreach (both variants)
        out.addAll(analyzer.analyze(List.of(
                block("approval", "gate", Map.of("message", "Go?", "on_no", "fail", "options", List.of("Yes", "Ok"))),
                block("foreach", "each", Map.of("items_command", "ls", "command", "echo {{ item }}")),
                block("foreach", "each2", Map.of("items_command", "ls", "items", List.of("a"), "items_source", "list",
                        "command", "echo {{ item }}"))), null));
        // Key: unknown with suggestion / block level / without hint, "wirkt nur mit", required missing (with
        // "gesetzt ist"), invalid value, nested path with names
        Map<String, Object> nested = new HashMap<>();
        nested.put("type", "simple");
        nested.put("name", "report");
        nested.put("config", Map.of("prompt", "Write the report", "on_failure", "fail"));
        out.addAll(analyzer.analyze(List.of(
                block("shell", "s", Map.of("command", "set -euo pipefail\nls", "timeout_second", 60, "name", "x")),
                block("retry", "r", Map.of("command", "npm test", "container_image", "node:24")),
                block("foreach", "f", Map.of("items", List.of("a"), "comand", "echo")),
                block("switch", "w", Map.of("expression", "{{ previous_output }}",
                        "cases", List.of(Map.of("value", "GREEN", "blocks", List.of(nested))))),
                // Old key name: alone (rename) and next to the new one (remove)
                block("sub-workflow", "old", Map.of("workflowId", "abc")),
                block("sub-workflow", "both", Map.of("workflow_id", "abc", "workflowId", "abc"))), null));
        // Own platform: self-api-call (shell and task), direct-db-access (app_settings, platform DB, sqlite3)
        out.addAll(analyzer.analyze(List.of(
                block("shell", "api", Map.of("command", "set -euo pipefail\ncurl http://localhost:9090/api/v1/todos")),
                block("simple", "ask", Map.of("command", "Run curl http://localhost:9090/api/v1/workflows and summarise")),
                block("shell", "settings", Map.of("command", "set -euo pipefail\necho app_settings")),
                block("shell", "db", Map.of("command", "set -euo pipefail\nsqlite3 data/automation.db 'SELECT 1'")),
                block("shell", "lite", Map.of("command", "set -euo pipefail\nsqlite3 project.db .dump"))), null));
        // Shape: dead-nested-blocks, unknown-block-field (config key, near spelling, otherwise; nested)
        BlockDefinition loose = block("shell", "loose", Map.of("command", "set -euo pipefail\nls"));
        loose.putUnknownField("on_failure", "continue");
        loose.putUnknownField("skill_ids", List.of("s"));
        loose.putUnknownField("comment", "note");
        Map<String, Object> strayNested = new HashMap<>();
        strayNested.put("type", "shell");
        strayNested.put("config", Map.of("command", "set -euo pipefail\nls"));
        strayNested.put("timeout_seconds", 60);
        out.addAll(analyzer.analyze(List.of(
                block("foreach", "dead", Map.of("items", List.of("a"), "command", "echo {{ item }}",
                        "blocks", List.of(Map.of("type", "simple", "config", Map.of("command", "x"))))),
                loose,
                block("switch", "sw", Map.of("expression", "x", "default", List.of(strayNested)))), null));
        return out;
    }

    @Test
    void jederBefundTraegtBeideSprachen() {
        List<AnalyzerFinding> findings = allFindings();

        assertThat(findings).isNotEmpty().allSatisfy(f -> {
            assertThat(f.detail()).as("detail von %s", f.rule()).isNotBlank();
            assertThat(f.detailEn()).as("detailEn von %s", f.rule()).isNotBlank().isNotEqualTo(f.detail());
            assertThat(GERMAN.matcher(f.detailEn()).find())
                    .as("detailEn von %s klingt deutsch: %s", f.rule(), f.detailEn()).isFalse();
        });
    }

    @Test
    void dieFixturesLoesenJedeRegelAus() throws IOException {
        Set<String> declared = new TreeSet<>();
        try (Stream<Path> files = Files.list(RULES_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = RULE_KEY.matcher(Files.readString(file));
                while (m.find()) declared.add(m.group(1));
            }
        }
        Set<String> covered = new TreeSet<>();
        allFindings().forEach(f -> covered.add(f.rule()));

        assertThat(declared).as("Regeln in den Quellen").hasSizeGreaterThanOrEqualTo(15);
        assertThat(covered).containsAll(declared);
    }

    @Test
    void dieTextbausteineSindAbgedeckt() {
        List<String> en = allFindings().stream().map(AnalyzerFinding::detailEn).toList();

        assertThat(en).anyMatch(t -> t.contains("did you mean timeout_seconds?"))
                .anyMatch(t -> t.contains("name belongs on the block level"))
                .anyMatch(t -> t.contains("only takes effect with"))
                .anyMatch(t -> t.contains("(comand is set instead)"))
                .anyMatch(t -> t.startsWith("Block 4 › cases[0] › Block 1 \"report\": "))
                .anyMatch(t -> t.contains("in the nested block 'inner'"))
                .anyMatch(t -> t.contains("the block runs with zero items"))
                .anyMatch(t -> t.contains("the items come from items"))
                .anyMatch(t -> t.contains("config.blocks (foreach) with 1 block entries never runs"))
                .anyMatch(t -> t.contains("on_failure sits next to config instead of inside it"))
                .anyMatch(t -> t.endsWith("rename the key to workflow_id"))
                .anyMatch(t -> t.endsWith("workflow_id is set as well and wins, remove workflowId"))
                .anyMatch(t -> t.contains("did you mean skillIds?"))
                .anyMatch(t -> t.contains("unknown block field comment"))
                .anyMatch(t -> t.startsWith("Block 3 › default › Block 1: timeout_seconds sits next to config"));
    }

    @Test
    void ofVerlangtBeideTexte() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                AnalyzerFinding.of("r", AnalyzerFinding.Severity.LOW, 0, null, "deutsch", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailWaehltNachSprache_undFaelltAufDeutschZurueck() {
        var both = AnalyzerFinding.of("r", AnalyzerFinding.Severity.LOW, 0, null, "deutsch", "english");
        var germanOnly = new AnalyzerFinding("r", AnalyzerFinding.Severity.LOW, 0, null, "deutsch");

        assertThat(both.detail("en")).isEqualTo("english");
        assertThat(both.detail("EN")).isEqualTo("english");
        assertThat(both.detail("de")).isEqualTo("deutsch");
        assertThat(both.detail(null)).isEqualTo("deutsch");
        assertThat(germanOnly.detail("en")).isEqualTo("deutsch");
    }
}
