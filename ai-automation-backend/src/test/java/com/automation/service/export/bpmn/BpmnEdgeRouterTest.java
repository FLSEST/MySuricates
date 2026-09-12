package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnEdgeRouterTest {

    private static BpmnModel.Node at(String id, double x, double y, double w, double h) {
        BpmnModel.Node n = new BpmnModel.Node(id, BpmnModel.Kind.TASK);
        n.x = x; n.y = y; n.w = w; n.h = h;
        return n;
    }

    @Test
    void sameRow_straightLineFromRightEdgeToLeftEdge() {
        BpmnModel.Node a = at("a", 0, 0, 100, 80), b = at("b", 250, 0, 100, 80);
        BpmnModel.Edge e = new BpmnModel.Edge("f", "a", "b");

        new BpmnEdgeRouter(Map.of("a", a, "b", b)).route(e);

        assertThat(e.waypoints).containsExactly(new double[]{100, 40}, new double[]{250, 40});
    }

    @Test
    void downwards_leavesBottomThenGoesRight() {
        BpmnModel.Node a = at("a", 0, 0, 50, 50), b = at("b", 200, 150, 100, 80);
        BpmnModel.Edge e = new BpmnModel.Edge("f", "a", "b");

        new BpmnEdgeRouter(Map.of("a", a, "b", b)).route(e);

        assertThat(e.waypoints).containsExactly(new double[]{25, 50}, new double[]{25, 190}, new double[]{200, 190});
    }

    @Test
    void upwards_leavesRightThenEntersBottomOfTarget() {
        BpmnModel.Node a = at("a", 200, 150, 100, 80), b = at("b", 400, 0, 50, 50);
        BpmnModel.Edge e = new BpmnModel.Edge("f", "a", "b");

        new BpmnEdgeRouter(Map.of("a", a, "b", b)).route(e);

        assertThat(e.waypoints).containsExactly(new double[]{300, 190}, new double[]{425, 190}, new double[]{425, 50});
    }
}
