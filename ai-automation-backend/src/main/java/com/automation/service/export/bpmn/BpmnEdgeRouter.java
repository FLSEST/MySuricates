package com.automation.service.export.bpmn;

import java.util.List;
import java.util.Map;

/** Orthogonal edge routing (spec §5). */
final class BpmnEdgeRouter {

    private final Map<String, BpmnModel.Node> nodeIndex;

    BpmnEdgeRouter(Map<String, BpmnModel.Node> nodeIndex) {
        this.nodeIndex = nodeIndex;
    }

    void routeChildEdges(List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            n.childEdges.forEach(this::route);
            routeChildEdges(n.children);
        }
    }

    void route(BpmnModel.Edge e) {
        BpmnModel.Node s = nodeIndex.get(e.sourceRef);
        BpmnModel.Node t = nodeIndex.get(e.targetRef);
        double sy = s.y + s.h / 2, ty = t.y + t.h / 2;
        if (Math.abs(sy - ty) < 1) {
            e.waypoints.add(new double[]{s.x + s.w, sy});
            e.waypoints.add(new double[]{t.x, ty});
        } else if (ty > sy) { // downwards: out of the source at the bottom, then to the right into the target
            double sx = s.x + s.w / 2;
            e.waypoints.add(new double[]{sx, s.y + s.h});
            e.waypoints.add(new double[]{sx, ty});
            e.waypoints.add(new double[]{t.x, ty});
        } else { // upwards: out of the source on the right, then at the top into the target
            double tx = t.x + t.w / 2;
            e.waypoints.add(new double[]{s.x + s.w, sy});
            e.waypoints.add(new double[]{tx, sy});
            e.waypoints.add(new double[]{tx, t.y + t.h});
        }
    }
}
