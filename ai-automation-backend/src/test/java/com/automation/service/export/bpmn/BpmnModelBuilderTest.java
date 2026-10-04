package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnModelBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BpmnModel build(com.automation.entity.Workflow wf) {
        return new BpmnModelBuilder(objectMapper).build(wf);
    }

    @Test
    void linearerWorkflowWirdZuStartTasksEnde() {
        BpmnModel model = build(WorkflowFixtures.linear());

        assertThat(model.processName).isEqualTo("Linear Demo");
        assertThat(model.nodes).hasSize(5); // start + 3 Tasks + end
        assertThat(model.nodes.get(0).kind).isEqualTo(BpmnModel.Kind.START_EVENT);
        assertThat(model.nodes.get(1).kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(model.nodes.get(1).name).isEqualTo("Daten sammeln");
        assertThat(model.nodes.get(1).script).isEqualTo("git log --oneline -20");
        assertThat(model.nodes.get(2).kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
        assertThat(model.nodes.get(3).kind).isEqualTo(BpmnModel.Kind.USER_TASK);
        assertThat(model.nodes.get(4).kind).isEqualTo(BpmnModel.Kind.END_EVENT);
        assertThat(model.edges).hasSize(4); // lückenlose Kette
        assertThat(model.nodes.get(1).configJson).contains("git log");
    }

    @Test
    void leererWorkflowErgibtStartDirektZuEnde() {
        BpmnModel model = build(WorkflowFixtures.workflow("Leer", List.of()));

        assertThat(model.nodes).hasSize(2);
        assertThat(model.edges).hasSize(1);
        assertThat(model.edges.get(0).sourceRef).isEqualTo(model.nodes.get(0).id);
        assertThat(model.edges.get(0).targetRef).isEqualTo(model.nodes.get(1).id);
    }

    @Test
    void unbekannterTypWirdGenerischerTaskMitWarnung() {
        BpmnModel model = build(WorkflowFixtures.workflow("Unbekannt", List.of(
                WorkflowFixtures.block("quantum", null, Map.of()))));

        BpmnModel.Node task = model.nodes.get(1);
        assertThat(task.kind).isEqualTo(BpmnModel.Kind.TASK);
        assertThat(task.warning).contains("quantum");
    }

    @Test
    void koordinatenLiegenAufDerHauptachse() {
        BpmnModel model = build(WorkflowFixtures.linear());

        for (BpmnModel.Node n : model.nodes) {
            // Alle Knoten eines linearen Workflows sind auf y=200 zentriert
            assertThat(n.y + n.h / 2).isEqualTo(200.0);
        }
        // X-Positionen streng aufsteigend
        for (int i = 1; i < model.nodes.size(); i++) {
            assertThat(model.nodes.get(i).x).isGreaterThan(model.nodes.get(i - 1).x);
        }
        // Jede Kante hat nach dem Routing mindestens 2 Waypoints
        for (BpmnModel.Edge e : model.edges) {
            assertThat(e.waypoints.size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void conditionalWirdZuGatewayPaarMitDefaultFlow() {
        BpmnModel model = build(WorkflowFixtures.workflow("Cond", List.of(
                WorkflowFixtures.block("conditional", null, Map.of(
                        "condition", "Tests fehlgeschlagen?",
                        "then_command", "Analysiere die Fehler",
                        "execution_mode", "claude")))));

        List<BpmnModel.Node> gateways = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.EXCLUSIVE_GATEWAY).toList();
        assertThat(gateways).hasSize(2);
        BpmnModel.Node split = gateways.get(0);
        assertThat(split.name).isEqualTo("Tests fehlgeschlagen?");
        assertThat(split.defaultFlowId).isNotNull();
        // Default-Flow (kein else_command) geht direkt zum Merge-Gateway
        BpmnModel.Edge defaultFlow = model.edges.stream()
                .filter(e -> e.id.equals(split.defaultFlowId)).findFirst().orElseThrow();
        assertThat(defaultFlow.targetRef).isEqualTo(gateways.get(1).id);
        // Then-Zweig liegt unterhalb der Hauptachse
        BpmnModel.Node thenTask = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SERVICE_TASK).findFirst().orElseThrow();
        assertThat(thenTask.y + thenTask.h / 2).isEqualTo(350.0); // 200 + ROW_STEP
    }

    @Test
    void switchMitCasesUndDefaultBlocks() {
        BpmnModel model = build(WorkflowFixtures.workflow("Switch", List.of(
                WorkflowFixtures.block("switch", null, Map.of(
                        "expression", "{{ status }}",
                        "cases", List.of(
                                Map.of("value", "RED", "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "echo alarm")))),
                                Map.of("value", "GREEN", "blocks", List.of())),
                        "default", List.of(
                                Map.of("type", "simple", "config", Map.of("command", "Prüfe manuell"))))))));

        List<BpmnModel.Node> gateways = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.EXCLUSIVE_GATEWAY).toList();
        assertThat(gateways).hasSize(2);
        assertThat(gateways.get(0).name).isEqualTo("{{ status }}");
        // RED-Case: scriptTask; leerer GREEN-Case: Direktkante mit Namen "GREEN"
        assertThat(model.nodes.stream().anyMatch(n -> n.kind == BpmnModel.Kind.SCRIPT_TASK
                && "echo alarm".equals(n.script))).isTrue();
        assertThat(model.edges.stream().anyMatch(e -> "GREEN".equals(e.name))).isTrue();
        // Default-Zweig existiert als serviceTask und ist der Default-Flow des Splits
        assertThat(gateways.get(0).defaultFlowId).isNotNull();
        assertThat(model.nodes.stream().anyMatch(n -> n.kind == BpmnModel.Kind.SERVICE_TASK)).isTrue();
    }

    @Test
    void switchRowsUeberlappenNichtBeiVerschachteltemKontrollfluss() {
        // Finding 2 des Branch-Reviews: Case A enthält einen verschachtelten conditional-Block
        // (then-Zweig bei +ROW_STEP), Case B einen einfachen Shell-Task. Bei einem naiven
        // rowIndex * ROW_STEP würde Case B (Achse bei 2 * ROW_STEP) mit Case As then-Zweig
        // (bei 1 * ROW_STEP + ROW_STEP) kollidieren; das höhenbewusste Layout muss das verhindern.
        BpmnModel model = build(WorkflowFixtures.workflow("SwitchNested", List.of(
                WorkflowFixtures.block("switch", null, Map.of(
                        "expression", "{{ status }}",
                        "cases", List.of(
                                Map.of("value", "A", "blocks", List.of(
                                        Map.of("type", "conditional", "config", Map.of(
                                                "condition", "Verschachtelt?",
                                                "then_command", "Verarbeite verschachtelt")))),
                                Map.of("value", "B", "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "echo case-b"))))))))));

        BpmnModel.Node splitA = model.nodes.stream()
                .filter(n -> "Verschachtelt?".equals(n.name)).findFirst().orElseThrow();
        BpmnModel.Node thenA = model.nodes.stream()
                .filter(n -> n.name != null && n.name.startsWith("KI: Verarbeite verschachtelt"))
                .findFirst().orElseThrow();
        BpmnModel.Node taskB = model.nodes.stream()
                .filter(n -> "echo case-b".equals(n.script)).findFirst().orElseThrow();

        double maxExtentCaseA = Math.max(splitA.y + splitA.h, thenA.y + thenA.h);
        // Case Bs Task-Mitte liegt unterhalb der maximalen Ausdehnung von Case A -> keine Überlappung.
        assertThat(taskB.y + taskB.h / 2).isGreaterThan(maxExtentCaseA);
    }

    @Test
    void switchMitZweiForeachsUeberlappenNicht() {
        // Fix für Findings 2: Zwei foreach-Blöcke in verschiedenen Switch-Cases
        // (case 0 und case 1) dürfen sich nicht vertikal überlappen. Jeder foreach wird
        // zu einem SubProcess mit Halbhöhe SUB_PAD*2 = 80. Die attachRow-Puffergröße muss
        // diesen Überhang abdecken.
        BpmnModel model = build(WorkflowFixtures.workflow("SwitchForeachLoop", List.of(
                WorkflowFixtures.block("switch", null, Map.of(
                        "expression", "{{ mode }}",
                        "cases", List.of(
                                Map.of("value", "case0", "blocks", List.of(
                                        Map.of("type", "foreach", "config", Map.of(
                                                "variable_name", "x",
                                                "command", "Tu was",
                                                "execution_mode", "claude")))),
                                Map.of("value", "case1", "blocks", List.of(
                                        Map.of("type", "foreach", "config", Map.of(
                                                "variable_name", "y",
                                                "command", "Tu was auch",
                                                "execution_mode", "claude"))))))))));

        List<BpmnModel.Node> subProcesses = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SUB_PROCESS)
                .sorted((a, b) -> Double.compare(a.y, b.y))
                .toList();

        assertThat(subProcesses).hasSize(2);
        BpmnModel.Node upper = subProcesses.get(0);
        BpmnModel.Node lower = subProcesses.get(1);

        // Lower SubProcess muss unterhalb der Upper SubProcess liegen (keine Überlappung)
        assertThat(lower.y).isGreaterThanOrEqualTo(upper.y + upper.h);
    }

    @Test
    void foreachWirdZuSubProcessMitMultiInstance() {
        BpmnModel model = build(WorkflowFixtures.workflow("Loop", List.of(
                WorkflowFixtures.block("foreach", null, Map.of(
                        "variable_name", "chunk",
                        "command", "Erstelle einen Song für {{ chunk }}",
                        "execution_mode", "claude")))));

        BpmnModel.Node sub = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SUB_PROCESS).findFirst().orElseThrow();
        assertThat(sub.loop).isEqualTo("multiInstanceSequential");
        assertThat(sub.name).isEqualTo("Für jedes chunk");
        assertThat(sub.children).hasSize(3);
        assertThat(sub.children.get(0).kind).isEqualTo(BpmnModel.Kind.START_EVENT);
        assertThat(sub.children.get(1).kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
        assertThat(sub.children.get(2).kind).isEqualTo(BpmnModel.Kind.END_EVENT);
        assertThat(sub.childEdges).hasSize(2);
        // Kinder liegen innerhalb der SubProcess-Bounds
        for (BpmnModel.Node child : sub.children) {
            assertThat(child.x).isGreaterThanOrEqualTo(sub.x);
            assertThat(child.x + child.w).isLessThanOrEqualTo(sub.x + sub.w);
            assertThat(child.y).isGreaterThanOrEqualTo(sub.y);
            assertThat(child.y + child.h).isLessThanOrEqualTo(sub.y + sub.h);
        }
    }

    /** a5c9fbbf: foreach mit workflow_id ruft je Item einen Workflow auf — CallActivity mit Multi-Instance. */
    @Test
    void foreachMitWorkflowIdWirdZuCallActivityMitMultiInstance() {
        BpmnModel model = build(WorkflowFixtures.workflow("LoopWf", List.of(
                WorkflowFixtures.block("foreach", "je Feature", Map.of(
                        "variable_name", "feature", "workflow_id", "wf-feature")))));

        assertThat(model.nodes).noneMatch(n -> n.kind == BpmnModel.Kind.SUB_PROCESS);
        BpmnModel.Node call = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.CALL_ACTIVITY).findFirst().orElseThrow();
        assertThat(call.loop).isEqualTo("multiInstanceSequential");
        assertThat(call.calledElement).isEqualTo("wf-feature");
        assertThat(call.blockType).isEqualTo("foreach");
        assertThat(call.name).isEqualTo("je Feature");
    }

    @Test
    void whileWirdZuSubProcessMitStandardLoop() {
        BpmnModel model = build(WorkflowFixtures.workflow("While", List.of(
                WorkflowFixtures.block("while", null, Map.of(
                        "command", "npm test 2>&1 | tail -5",
                        "condition_command", "npm test > /dev/null || echo weiter",
                        "execution_mode", "shell")))));

        BpmnModel.Node sub = model.nodes.stream()
                .filter(n -> n.kind == BpmnModel.Kind.SUB_PROCESS).findFirst().orElseThrow();
        assertThat(sub.loop).isEqualTo("standardLoop");
        assertThat(sub.documentation).startsWith("Wiederholen solange:");
        assertThat(sub.children.get(1).kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
    }

    @Test
    void komprimierungsBloeckeWerdenScriptUndServiceTask() {
        BpmnModel model = build(WorkflowFixtures.workflow("Komprimierung", List.of(
                WorkflowFixtures.block("compress", "kompakt", Map.of("source", "Build",
                        "stages", List.of(Map.of("op", "lines.tail", "lines", 50)))),
                WorkflowFixtures.block("compress-ai", null, Map.of("source", "kompakt", "instruction", "Fehler nennen")))));

        BpmnModel.Node compress = model.nodes.stream().filter(n -> "compress".equals(n.blockType)).findFirst().orElseThrow();
        BpmnModel.Node ai = model.nodes.stream().filter(n -> "compress-ai".equals(n.blockType)).findFirst().orElseThrow();
        assertThat(compress.kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(compress.scriptFormat).isEqualTo("mysuricates-compress");
        assertThat(compress.script).isEqualTo("source(Build) | lines.tail(50)");
        assertThat(compress.warning).isNull();
        assertThat(ai.kind).isEqualTo(BpmnModel.Kind.SERVICE_TASK);
    }
}
