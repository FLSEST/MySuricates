package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    /**
     * Map with fixed order — the ONLY permitted map source in this fixture.
     *
     * <p>{@code Map.of} iterates randomly per JVM (SALT in {@code ImmutableCollections}); in a
     * golden file this order ends up as config JSON and makes the file unstable between two runs.
     * For block configs this goes unnoticed because {@link #block} copies them into a
     * {@code LinkedHashMap} — but nested maps in lists (switch cases,
     * branch blocks) go into the JSON unchanged. Exactly there {@code branching.bpmn}
     * was JVM-unstable (TODO 7557b928/df5c689d).
     *
     * <p>That {@code Map.of} does not reappear here is pinned by
     * {@link WorkflowFixturesOrderGuardTest}.
     */
    static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        // LinkedHashMap, not HashMap: this is how the golden file reads the keys in the
        // order in which the fixture writes them — and thus mirrors the
        // production path, where Jackson also delivers LinkedHashMap.
        b.setConfig(new LinkedHashMap<>(config));
        return b;
    }

    static Workflow workflow(String name, List<BlockDefinition> blocks) {
        return Workflow.builder().id("wf-test").name(name).blocks(blocks).build();
    }

    static Workflow linear() {
        return workflow("Linear Demo", List.of(
                block("shell", "Daten sammeln", ordered("command", "git log --oneline -20")),
                block("simple", null, ordered("command", "Fasse die Historie zusammen:\n{{ previous_output }}")),
                block("approval", null, ordered("question", "Bericht freigeben?"))));
    }

    /**
     * Data-flow fixture: {@code tests} produces, {@code compress} consumes (config {@code source})
     * and produces, {@code report} consumes via {@code block.compress.output}.
     */
    static Workflow dataFlow() {
        return workflow("Datenfluss", List.of(
                block("shell", "tests", ordered("command", "mvn test")),
                block("compress", "compress", ordered("source", "tests", "max_chars", 5000)),
                block("simple", "report", ordered("command", "Bericht schreiben:\n{{ block.compress.output }}"))));
    }

    /**
     * Data flow across a branching (7557b928): {@code tests} flows into the case block
     * {@code analyse}, whose output is consumed by the later top-level block {@code report}.
     * The endpoints of the associations are the branch tasks, not the gateway.
     */
    static Workflow branching() {
        return workflow("Verzweigter Datenfluss", List.of(
                block("shell", "tests", ordered("command", "mvn test")),
                block("switch", "verteiler", ordered(
                        "expression", "{{ gate }}",
                        "cases", List.of(ordered("value", "RED", "blocks", List.of(
                                ordered("type", "simple", "name", "analyse", "config", ordered(
                                        "command", "Analysiere den Fehlschlag:\n{{ block.tests.output }}"))))),
                        "default", List.of(ordered("type", "shell", "name", "notiz", "config", ordered(
                                "command", "echo 'alles gruen'"))))),
                block("simple", "report", ordered("command", "Bericht schreiben:\n{{ block.analyse.output }}"))));
    }

    /**
     * Catalog of all export fixtures for the roundtrip proof: every block type (WORKFLOW-GUIDE),
     * unknown type, named/nameless blocks, nested switch cases (conditional,
     * foreach, further switch), special characters and the empty process.
     */
    static List<Workflow> roundtripCatalog() {
        return List.of(
                workflow("Leer", List.of()),
                linear(),
                dataFlow(),
                branching(),
                // step_N_output: 0-based, without a wrapper block before it ⇒ edge to the first block
                workflow("StepIndex", List.of(
                        block("shell", "erst", ordered("command", "mvn test")),
                        block("simple", "dann", ordered("command", "Fasse zusammen:\n{{ step_0_output }}")))),
                workflow("Cond", List.of(block("conditional", null, ordered(
                        "condition", "Fehler?", "then_command", "Analysiere", "else_command", "Weiter",
                        "execution_mode", "shell")))),
                workflow("CondOhneElse", List.of(block("conditional", "Pruefung", ordered(
                        "condition", "test -f out.txt", "then_command", "cat out.txt", "execution_mode", "shell")))),
                workflow("Loop", List.of(block("foreach", "Songs erstellen", ordered(
                        "variable_name", "item", "items", List.of("a", "b"), "command", "Verarbeite {{ item }}")))),
                // foreach with workflow_id (a5c9fbbf): call activity per item instead of sub-process with command
                workflow("LoopWorkflow", List.of(block("foreach", "je Feature", ordered(
                        "variable_name", "feature", "items", List.of("a", "b"), "workflow_id", "wf-feature",
                        "context", ordered("feature_id", "{{ feature.id }}"))))),
                workflow("While", List.of(block("while", null, ordered(
                        "command", "npm test", "condition_command", "echo weiter", "execution_mode", "shell",
                        "max_iterations", 5)))),
                workflow("Switch", List.of(block("switch", null, ordered(
                        "expression", "{{ gate }}",
                        "cases", List.of(ordered("value", "RED", "blocks", List.of(
                                ordered("type", "shell", "name", "alarm", "config", ordered("command", "echo alarm"))))),
                        "default", List.of(ordered("type", "simple", "config", ordered("command", "Prüfe"))))))),
                workflow("SwitchNested", List.of(block("switch", "Verteiler", ordered(
                        "expression", "{{ status }}",
                        "cases", List.of(
                                ordered("value", "A", "blocks", List.of(
                                        ordered("type", "conditional", "config", ordered(
                                                "condition", "x", "then_command", "y", "else_command", "z")),
                                        ordered("type", "foreach", "name", "inner", "config", ordered(
                                                "variable_name", "f", "command", "echo {{ f }}")))),
                                ordered("value", "B", "blocks", List.of(
                                        ordered("type", "switch", "config", ordered(
                                                "expression", "{{ deep }}",
                                                "cases", List.of(ordered("value", "1", "blocks", List.of(
                                                        ordered("type", "shell", "config", ordered("command", "true"))))))))),
                                ordered("value", "C", "blocks", List.of())),
                        "default", List.of())))),
                workflow("AlleTypen", List.of(
                        block("simple", null, ordered("command", "Fasse zusammen", "effort", "low")),
                        block("shell", "Build", ordered("command", "npm run build", "timeout_seconds", 600, "on_failure", "continue")),
                        block("retry", null, ordered("command", "flaky", "max_retries", 3, "execution_mode", "shell")),
                        block("agentic", "Agent", ordered("goal", "Finde Bugs", "max_turns", 10, "max_total_tokens", 50000)),
                        block("approval", "Freigabe", ordered("question", "OK?", "options", List.of("Yes", "No"))),
                        block("sub-workflow", "Unterprozess", ordered("workflow_id", "wf-child-1", "context", ordered("k", "v"))),
                        block("skill-capability", null, ordered("capability", "git.commit", "allow_writes", true)),
                        block("human-input", null, ordered("prompt", "Wie heißt du?", "variable", "name")),
                        block("integration", null, ordered("provider", "jira", "action", "createIssue", "params", ordered("project", "AP"))),
                        block("document", null, ordered("file_path", "docs/report.md", "mode", "append")),
                        block("todo-create", null, ordered("title", "Neu", "priority", "HIGH", "tags", List.of("bug"))),
                        block("todo-update", null, ordered("todo_id", "{{ todo_id }}", "status", "IN_PROGRESS")),
                        block("todo-close", null, ordered("todo_id", "{{ todo_id }}")),
                        block("compress", "kompakt", ordered("source", "Build", "max_chars", 5000,
                                "stages", List.of(ordered("op", "lines.tail", "lines", 50)))),
                        block("compress-ai", null, ordered("source", "kompakt", "instruction", "Fehler nennen", "max_chars", 1000)))),
                workflow("Unbekannt", List.of(block("quantum", null, ordered()))),
                workflow("Sonderzeichen & <Umlaute> \"Ä\"", List.of(
                        block("shell", "A & B <C>", ordered(
                                "command", "echo \"Grüße\" && cat <<'EOF'\nZeile 1 -- Kommentar\n]]> & </script>\nEOF",
                                "note", "Tab\tund\r\nUmbruch", "leer", "", "zahl", 1.5, "flag", false)),
                        block("simple", "ümläut", ordered("command", "Sag 'hallo' → \"welt\"")))));
    }
}
