package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;

/** Teil-Graph mit relativen Koordinaten; wird beim Einhängen verschoben. */
class BpmnSegment {
    final List<BpmnModel.Node> nodes = new ArrayList<>();
    final List<BpmnModel.Edge> edges = new ArrayList<>();
    String entryId, exitId;
    double width;

    void translate(double dx, double dy) {
        translateNodes(nodes, dx, dy);
    }

    static void translateNodes(List<BpmnModel.Node> list, double dx, double dy) {
        for (BpmnModel.Node n : list) {
            n.x += dx;
            n.y += dy;
            translateNodes(n.children, dx, dy);
        }
    }
}
