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
}
