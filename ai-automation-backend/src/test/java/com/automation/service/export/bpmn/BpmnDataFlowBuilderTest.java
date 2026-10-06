package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnDataFlowBuilder.DATA_H;
import static com.automation.service.export.bpmn.BpmnDataFlowBuilder.DATA_ROW_GAP;
import static com.automation.service.export.bpmn.BpmnDataFlowBuilder.DATA_W;
import static com.automation.service.export.bpmn.WorkflowFixtures.block;
import static com.automation.service.export.bpmn.WorkflowFixtures.ordered;
import static org.assertj.core.api.Assertions.assertThat;

/** Data objects, associations and coordinates from the detected references. */
class BpmnDataFlowBuilderTest {

    private final BpmnModel model = new BpmnModel();
    private final Map<String, String> activities = new HashMap<>();

    private BpmnModel.Node task(String id, String name, double x) {
        BpmnModel.Node n = new BpmnModel.Node(id, BpmnModel.Kind.TASK);
        n.name = name;
        n.x = x;
        n.y = 160;
        n.w = 100;
        n.h = 80;
        model.nodes.add(n);
        activities.put(id, id);
        return n;
    }

    @Test
    void ohneReferenzenBleibtDasModellUnveraendert() {
        task("b_0", "A", 100);
        task("b_1", "B", 350);
        List<BlockDefinition> blocks = List.of(
                block("shell", "a", ordered("command", "echo")),
                block("shell", "b", ordered("command", "echo")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects).isEmpty();
        assertThat(model.dataAssociations).isEmpty();
    }

    @Test
    void erzeugerBekommtEinDatenobjektUnterSeinemTaskUndDerKonsumentEineEingabe() {
        BpmnModel.Node producer = task("b_0", "A", 100);
        BpmnModel.Node consumer = task("b_1", "B", 350);
        List<BlockDefinition> blocks = List.of(
                block("shell", "Daten holen", ordered("command", "echo")),
                block("simple", null, ordered("command", "Fasse zusammen: {{ previous_output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects).hasSize(1);
        BpmnModel.DataObject data = model.dataObjects.get(0);
        assertThat(data.id).isEqualTo("data_daten_holen");
        assertThat(data.referenceId).isEqualTo("dataref_daten_holen");
        assertThat(data.name).isEqualTo("Ausgabe: Daten holen");
        assertThat(data.w).isEqualTo(DATA_W);
        assertThat(data.h).isEqualTo(DATA_H);
        assertThat(data.x).isEqualTo(100 + 50 - DATA_W / 2);
        assertThat(data.y).isEqualTo(160 + 80 + DATA_ROW_GAP);

        assertThat(producer.dataOutputs).singleElement().satisfies(a -> {
            assertThat(a.id).isEqualTo("dataflow_out_0");
            assertThat(a.input).isFalse();
            assertThat(a.dataReferenceId).isEqualTo(data.referenceId);
            assertThat(a.waypoints.get(0)).containsExactly(150, 240);
            assertThat(a.waypoints.get(1)).containsExactly(150, data.y);
        });
        assertThat(consumer.dataInputs).singleElement().satisfies(a -> {
            assertThat(a.id).isEqualTo("dataflow_in_0");
            assertThat(a.input).isTrue();
            assertThat(a.waypoints.get(0)).containsExactly(data.x + DATA_W, data.y + DATA_H / 2);
            assertThat(a.waypoints.get(1)).containsExactly(400, 240);
        });
        assertThat(consumer.dataPropertyId).isEqualTo("b_1_data_input");
        assertThat(producer.dataPropertyId).isNull();
        assertThat(model.dataAssociations).hasSize(2);
    }

    @Test
    void mehrereKonsumentenTeilenSichEinDatenobjekt() {
        task("b_0", "A", 100);
        task("b_1", "B", 350);
        task("b_2", "C", 600);
        List<BlockDefinition> blocks = List.of(
                block("shell", "quelle", ordered("command", "echo")),
                block("simple", null, ordered("command", "{{ block.quelle.output }}")),
                block("simple", null, ordered("command", "{{ block.quelle.output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects).hasSize(1);
        assertThat(model.dataAssociations).extracting(a -> a.id)
                .containsExactly("dataflow_out_0", "dataflow_in_0", "dataflow_in_1");
    }

    @Test
    void referenzOhneAktivitaetsKnotenWirdUebersprungen() {
        task("b_1", "B", 350); // b_0 is a gateway and has no activity node
        List<BlockDefinition> blocks = List.of(
                block("conditional", "weiche", ordered("condition", "c")),
                block("simple", null, ordered("command", "{{ previous_output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects).isEmpty();
        assertThat(model.nodes.get(0).dataInputs).isEmpty();
        assertThat(model.nodes.get(0).dataPropertyId).isNull();
    }

    @Test
    void unbenannterErzeugerNimmtKnotennamenUndKollidierendeIdsBekommenSuffix() {
        task("b_0", "Shell", 100);
        task("b_1", "B", 350);
        // An existing node already occupies the id that the data object would get.
        BpmnModel.Node clash = new BpmnModel.Node("data_shell", BpmnModel.Kind.TASK);
        model.nodes.add(clash);
        List<BlockDefinition> blocks = List.of(
                block("shell", "  ", ordered("command", "echo")),
                block("simple", null, ordered("command", "{{ previous_output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        BpmnModel.DataObject data = model.dataObjects.get(0);
        assertThat(data.name).isEqualTo("Ausgabe: Shell");
        assertThat(data.id).isEqualTo("data_shell_2");
        assertThat(data.referenceId).isEqualTo("dataref_shell");
    }

    @Test
    void erzeugerOhneNamenFaelltAufDieKnotenIdZurueck() {
        task("b_0", null, 100);
        task("b_1", "B", 350);
        List<BlockDefinition> blocks = List.of(
                block("shell", null, ordered("command", "echo")),
                block("simple", null, ordered("command", "{{ previous_output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects.get(0).name).isEqualTo("Ausgabe: b_0");
    }

    @Test
    void datenobjektReiheLiegtUnterDemTiefstenKnoten() {
        BpmnModel.Node tall = task("b_0", "A", 100);
        tall.h = 300;
        task("b_1", "B", 350);
        List<BlockDefinition> blocks = List.of(
                block("shell", "a", ordered("command", "echo")),
                block("simple", null, ordered("command", "{{ previous_output }}")));

        BpmnDataFlowBuilder.apply(model, blocks, activities, "b");

        assertThat(model.dataObjects.get(0).y).isEqualTo(160 + 300 + DATA_ROW_GAP);
    }
}
