package com.automation.service.export.bpmn;

import java.util.List;
import java.util.Map;

/** Rechtwinkliges Kanten-Routing (Spec §5). */
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
        } else if (ty > sy) { // abwärts: unten aus der Quelle, dann nach rechts ins Ziel
            double sx = s.x + s.w / 2;
            e.waypoints.add(new double[]{sx, s.y + s.h});
            e.waypoints.add(new double[]{sx, ty});
            e.waypoints.add(new double[]{t.x, ty});
        } else { // aufwärts: rechts aus der Quelle, dann oben ins Ziel
            double tx = t.x + t.w / 2;
            e.waypoints.add(new double[]{s.x + s.w, sy});
            e.waypoints.add(new double[]{tx, sy});
            e.waypoints.add(new double[]{tx, t.y + t.h});
        }
    }
}
