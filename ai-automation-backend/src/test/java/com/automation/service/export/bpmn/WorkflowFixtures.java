package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    static Workflow workflow(String name, List<BlockDefinition> blocks) {
        return Workflow.builder().id("wf-test").name(name).blocks(blocks).build();
    }

    static Workflow linear() {
        return workflow("Linear Demo", List.of(
                block("shell", "Daten sammeln", Map.of("command", "git log --oneline -20")),
                block("simple", null, Map.of("command", "Fasse die Historie zusammen:\n{{ previous_output }}")),
                block("approval", null, Map.of("question", "Bericht freigeben?"))));
    }

    /**
     * Datenfluss-Fixture: {@code tests} erzeugt, {@code compress} konsumiert (Config {@code source})
     * und erzeugt, {@code report} konsumiert über {@code block.compress.output}.
     */
    static Workflow dataFlow() {
        return workflow("Datenfluss", List.of(
                block("shell", "tests", Map.of("command", "mvn test")),
                block("compress", "compress", Map.of("source", "tests", "max_chars", 5000)),
                block("simple", "report", Map.of("command", "Bericht schreiben:\n{{ block.compress.output }}"))));
    }

    /**
     * Katalog aller Export-Fixtures für den Roundtrip-Beweis: jeder Block-Typ (WORKFLOW-GUIDE),
     * unbekannter Typ, benannte/namenlose Blöcke, verschachtelte Switch-Cases (conditional,
     * foreach, weiterer switch), Sonderzeichen und der leere Prozess.
     */
    static List<Workflow> roundtripCatalog() {
        return List.of(
                workflow("Leer", List.of()),
                linear(),
                dataFlow(),
                // step_N_output: 0-basiert, ohne Wrapper-Block davor ⇒ Kante zum ersten Block
                workflow("StepIndex", List.of(
                        block("shell", "erst", Map.of("command", "mvn test")),
                        block("simple", "dann", Map.of("command", "Fasse zusammen:\n{{ step_0_output }}")))),
                workflow("Cond", List.of(block("conditional", null, Map.of(
                        "condition", "Fehler?", "then_command", "Analysiere", "else_command", "Weiter",
                        "execution_mode", "shell")))),
                workflow("CondOhneElse", List.of(block("conditional", "Pruefung", Map.of(
                        "condition", "test -f out.txt", "then_command", "cat out.txt", "execution_mode", "shell")))),
                workflow("Loop", List.of(block("foreach", "Songs erstellen", Map.of(
                        "variable_name", "item", "items", List.of("a", "b"), "command", "Verarbeite {{ item }}")))),
                workflow("While", List.of(block("while", null, Map.of(
                        "command", "npm test", "condition_command", "echo weiter", "execution_mode", "shell",
                        "max_iterations", 5)))),
                workflow("Switch", List.of(block("switch", null, Map.of(
                        "expression", "{{ gate }}",
                        "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                Map.of("type", "shell", "name", "alarm", "config", Map.of("command", "echo alarm"))))),
                        "default", List.of(Map.of("type", "simple", "config", Map.of("command", "Prüfe"))))))),
                workflow("SwitchNested", List.of(block("switch", "Verteiler", Map.of(
                        "expression", "{{ status }}",
                        "cases", List.of(
                                Map.of("value", "A", "blocks", List.of(
                                        Map.of("type", "conditional", "config", Map.of(
                                                "condition", "x", "then_command", "y", "else_command", "z")),
                                        Map.of("type", "foreach", "name", "inner", "config", Map.of(
                                                "variable_name", "f", "command", "echo {{ f }}")))),
                                Map.of("value", "B", "blocks", List.of(
                                        Map.of("type", "switch", "config", Map.of(
                                                "expression", "{{ deep }}",
                                                "cases", List.of(Map.of("value", "1", "blocks", List.of(
                                                        Map.of("type", "shell", "config", Map.of("command", "true"))))))))),
                                Map.of("value", "C", "blocks", List.of())),
                        "default", List.of())))),
                workflow("AlleTypen", List.of(
                        block("simple", null, Map.of("command", "Fasse zusammen", "effort", "low")),
                        block("shell", "Build", Map.of("command", "npm run build", "timeout_seconds", 600, "on_failure", "continue")),
                        block("retry", null, Map.of("command", "flaky", "max_retries", 3, "execution_mode", "shell")),
                        block("agentic", "Agent", Map.of("goal", "Finde Bugs", "max_turns", 10, "max_total_tokens", 50000)),
                        block("approval", "Freigabe", Map.of("question", "OK?", "options", List.of("Yes", "No"))),
                        block("sub-workflow", "Unterprozess", Map.of("workflow_id", "wf-child-1", "context", Map.of("k", "v"))),
                        block("skill-capability", null, Map.of("capability", "git.commit", "allow_writes", true)),
                        block("human-input", null, Map.of("prompt", "Wie heißt du?", "variable", "name")),
                        block("integration", null, Map.of("provider", "jira", "action", "createIssue", "params", Map.of("project", "AP"))),
                        block("document", null, Map.of("file_path", "docs/report.md", "mode", "append")),
                        block("todo-create", null, Map.of("title", "Neu", "priority", "HIGH", "tags", List.of("bug"))),
                        block("todo-update", null, Map.of("todo_id", "{{ todo_id }}", "status", "IN_PROGRESS")),
                        block("todo-close", null, Map.of("todo_id", "{{ todo_id }}")),
                        block("compress", "kompakt", Map.of("source", "Build", "max_chars", 5000,
                                "stages", List.of(Map.of("op", "lines.tail", "lines", 50)))),
                        block("compress-ai", null, Map.of("source", "kompakt", "instruction", "Fehler nennen", "max_chars", 1000)))),
                workflow("Unbekannt", List.of(block("quantum", null, Map.of()))),
                workflow("Sonderzeichen & <Umlaute> \"Ä\"", List.of(
                        block("shell", "A & B <C>", Map.of(
                                "command", "echo \"Grüße\" && cat <<'EOF'\nZeile 1 -- Kommentar\n]]> & </script>\nEOF",
                                "note", "Tab\tund\r\nUmbruch", "leer", "", "zahl", 1.5, "flag", false)),
                        block("simple", "ümläut", Map.of("command", "Sag 'hallo' → \"welt\"")))));
    }
}
