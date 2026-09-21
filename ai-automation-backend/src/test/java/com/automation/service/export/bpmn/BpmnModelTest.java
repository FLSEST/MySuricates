package com.automation.service.export.bpmn;

import com.automation.entity.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Das interne Modell: Grundzustand der Knoten/Kanten und Befüllung durch den Builder. */
class BpmnModelTest {

    @Test
    void neuerKnotenStartetOhneOptionaleAngabenUndMitLeerenListen() {
        BpmnModel.Node n = new BpmnModel.Node("t", BpmnModel.Kind.SCRIPT_TASK);

        assertThat(n.id).isEqualTo("t");
        assertThat(n.kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(n.scriptFormat).as("null bedeutet bash").isNull();
        assertThat(n.loop).isNull();
        assertThat(n.defaultFlowId).isNull();
        assertThat(n.dataPropertyId).isNull();
        assertThat(n.children).isEmpty();
        assertThat(n.childEdges).isEmpty();
        assertThat(n.dataInputs).isEmpty();
        assertThat(n.dataOutputs).isEmpty();
        assertThat(n.x + n.y + n.w + n.h).isZero();
    }

    @Test
    void kanteUndDatenelementeHaltenIhreReferenzen() {
        BpmnModel.Edge e = new BpmnModel.Edge("flow_1", "a", "b");
        assertThat(e.sourceRef).isEqualTo("a");
        assertThat(e.targetRef).isEqualTo("b");
        assertThat(e.name).isNull();
        assertThat(e.waypoints).isEmpty();

        BpmnModel.DataObject d = new BpmnModel.DataObject("data_x", "dataref_x", "Ausgabe: x");
        assertThat(d.referenceId).isEqualTo("dataref_x");
        assertThat(d.name).isEqualTo("Ausgabe: x");

        BpmnModel.DataAssociation in = new BpmnModel.DataAssociation("dataflow_in_0", "dataref_x", true);
        assertThat(in.input).isTrue();
        assertThat(in.dataReferenceId).isEqualTo("dataref_x");
    }

    @Test
    void builderFuelltProzessStartUndEnde() {
        Workflow wf = WorkflowFixtures.linear();

        BpmnModel model = new BpmnModelBuilder(new ObjectMapper()).build(wf);

        assertThat(model.processName).isEqualTo("Linear Demo");
        assertThat(model.processId).isEqualTo("process_linear_demo");
        assertThat(model.nodes).first().extracting(n -> n.kind).isEqualTo(BpmnModel.Kind.START_EVENT);
        assertThat(model.nodes).last().extracting(n -> n.kind).isEqualTo(BpmnModel.Kind.END_EVENT);
        assertThat(model.nodes).hasSize(5);
        assertThat(model.edges).hasSize(4);
        // Block 1 liest previous_output → genau ein Datenobjekt mit Aus- und Eingabe
        assertThat(model.dataObjects).hasSize(1);
        assertThat(model.dataAssociations).extracting(a -> a.input).containsExactly(false, true);
    }
}
