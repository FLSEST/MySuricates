package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnNodeFactoryTest {

    private final BpmnNodeFactory f = new BpmnNodeFactory(new ObjectMapper());

    @Test
    void node_makesIdsUniqueWithNumericSuffix() {
        BpmnModel.Node a = f.node("t", BpmnModel.Kind.TASK, "A", 100, 80);
        BpmnModel.Node b = f.node("t", BpmnModel.Kind.TASK, "B", 100, 80);
        BpmnModel.Node c = f.node("t", BpmnModel.Kind.TASK, "C", 100, 80);

        assertThat(a.id).isEqualTo("t");
        assertThat(b.id).isEqualTo("t_2");
        assertThat(c.id).isEqualTo("t_3");
        assertThat(f.nodeIndex()).containsKeys("t", "t_2", "t_3");
    }

    @Test
    void edge_idsCountUpFromZero() {
        assertThat(f.edge("a", "b", null).id).isEqualTo("flow_0");
        assertThat(f.edge("b", "c", "ja").id).isEqualTo("flow_1");
    }

    @Test
    void place_centersVerticallyOnRow() {
        BpmnModel.Node n = f.node("n", BpmnModel.Kind.TASK, "N", 100, 80);
        f.place(n, 30, 200);
        assertThat(n.x).isEqualTo(30);
        assertThat(n.y).isEqualTo(160);
    }

    @Test
    void commandTask_shellModeSetsScriptAndFirstLineDocumentation() {
        BpmnModel.Node n = f.commandTask("c", "echo hi\necho there", true);
        assertThat(n.kind).isEqualTo(BpmnModel.Kind.SCRIPT_TASK);
        assertThat(n.script).isEqualTo("echo hi\necho there");
        assertThat(n.documentation).isEqualTo("echo hi");
    }

    @Test
    void toJson_nullConfigBecomesEmptyObject() {
        assertThat(f.toJson(null)).isEqualTo("{}");
        assertThat(f.toJson(Map.of("a", 1))).isEqualTo("{\"a\":1}");
    }
}
