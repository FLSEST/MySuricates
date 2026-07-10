package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;

/** Internes BPMN-Modell: semantische Knoten/Kanten inkl. Diagramm-Koordinaten. */
class BpmnModel {

    enum Kind { START_EVENT, END_EVENT, TASK, SERVICE_TASK, SCRIPT_TASK, USER_TASK, EXCLUSIVE_GATEWAY, SUB_PROCESS, CALL_ACTIVITY }

    String processId;
    String processName;
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();

    static class Node {
        final String id;
        final Kind kind;
        String name;
        String documentation;
        String configJson;
        String script;          // nur SCRIPT_TASK
        String calledElement;   // nur CALL_ACTIVITY
        String loop;            // null | "multiInstanceSequential" | "standardLoop"
        String defaultFlowId;   // nur EXCLUSIVE_GATEWAY mit Default-Pfad
        String warning;         // erzeugt XML-Kommentar (unbekannter Blocktyp)
        final List<Node> children = new ArrayList<>();      // nur SUB_PROCESS
        final List<Edge> childEdges = new ArrayList<>();    // nur SUB_PROCESS
        double x, y, w, h;

        Node(String id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    static class Edge {
        final String id;
        final String sourceRef;
        final String targetRef;
        String name;
        final List<double[]> waypoints = new ArrayList<>();

        Edge(String id, String sourceRef, String targetRef) {
            this.id = id;
            this.sourceRef = sourceRef;
            this.targetRef = targetRef;
        }
    }
}
