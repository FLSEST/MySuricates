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
}
