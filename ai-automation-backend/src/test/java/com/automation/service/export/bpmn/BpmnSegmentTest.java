package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnSegmentTest {

    private static BpmnModel.Node node(String id, double x, double y) {
        BpmnModel.Node n = new BpmnModel.Node(id, BpmnModel.Kind.TASK);
        n.x = x;
        n.y = y;
        return n;
    }

    @Test
    void translateVerschiebtKnotenSamtVerschachteltenKindern() {
        BpmnModel.Node sub = node("sub", 10, 20);
        BpmnModel.Node child = node("child", 1, 2);
        BpmnModel.Node grandChild = node("grand", 3, 4);
        child.children.add(grandChild);
        sub.children.add(child);
        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(sub);
        seg.nodes.add(node("t", 0, 0));

        seg.translate(100, -5);

        assertThat(seg.nodes).extracting(n -> n.x, n -> n.y)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(110.0, 15.0),
                        org.assertj.core.groups.Tuple.tuple(100.0, -5.0));
        assertThat(child.x).isEqualTo(101);
        assertThat(child.y).isEqualTo(-3);
        assertThat(grandChild.x).isEqualTo(103);
        assertThat(grandChild.y).isEqualTo(-1);
    }

    @Test
    void translateLaesstKantenEinstiegUndBreiteUnberuehrt() {
        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(node("a", 0, 0));
        BpmnModel.Edge e = new BpmnModel.Edge("flow_0", "a", "b");
        e.waypoints.add(new double[]{5, 5});
        seg.edges.add(e);
        seg.entryId = "a";
        seg.exitId = "a";
        seg.width = 100;

        seg.translate(50, 50);

        assertThat(e.waypoints.get(0)).containsExactly(5, 5);
        assertThat(seg.entryId).isEqualTo("a");
        assertThat(seg.width).isEqualTo(100);
    }

    @Test
    void translateNodesAufLeererListeIstNoOp() {
        BpmnSegment.translateNodes(List.of(), 10, 10);
        BpmnSegment empty = new BpmnSegment();
        empty.translate(10, 10);
        assertThat(empty.nodes).isEmpty();
        assertThat(empty.entryId).isNull();
    }
}
