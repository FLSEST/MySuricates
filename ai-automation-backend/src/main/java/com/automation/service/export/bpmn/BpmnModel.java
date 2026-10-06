package com.automation.service.export.bpmn;

import java.util.ArrayList;
import java.util.List;

/** Internal BPMN model: semantic nodes/edges including diagram coordinates. */
class BpmnModel {

    enum Kind { START_EVENT, END_EVENT, TASK, SERVICE_TASK, SCRIPT_TASK, USER_TASK, EXCLUSIVE_GATEWAY, SUB_PROCESS, CALL_ACTIVITY }

    String processId;
    String processName;
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    /**
     * Data flow: one data object per producing block. Order = that of first use in
     * the reference list of the {@code DataFlowReferenceScanner}, sorted by (target, source),
     * not the block order.
     */
    final List<DataObject> dataObjects = new ArrayList<>();
    /** All data associations in render order — only for the diagram edges (DI). */
    final List<DataAssociation> dataAssociations = new ArrayList<>();

    static class Node {
        final String id;
        final Kind kind;
        String name;
        String documentation;
        String configJson;
        String blockType;       // original block type (e.g. "shell", "conditional") for lossless reimport
        String blockName;       // original block name (only if set) for lossless reimport
        String script;          // SCRIPT_TASK only
        String scriptFormat;    // SCRIPT_TASK only: null = bash
        String calledElement;   // CALL_ACTIVITY only
        String loop;            // null | "multiInstanceSequential" | "standardLoop"
        String defaultFlowId;   // EXCLUSIVE_GATEWAY with default path only
        String warning;         // produces an XML comment (unknown block type)
        final List<Node> children = new ArrayList<>();      // SUB_PROCESS only
        final List<Edge> childEdges = new ArrayList<>();    // SUB_PROCESS only
        final List<DataAssociation> dataInputs = new ArrayList<>();
        final List<DataAssociation> dataOutputs = new ArrayList<>();
        String dataPropertyId;  // target placeholder of the dataInputAssociation (only if dataInputs is not empty)
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

    /** Output of a block as a BPMN data object ({@code dataObject} + {@code dataObjectReference}). */
    static class DataObject {
        final String id;            // dataObject
        final String referenceId;   // dataObjectReference (target/source of the associations)
        final String name;
        double x, y, w, h;

        DataObject(String id, String referenceId, String name) {
            this.id = id;
            this.referenceId = referenceId;
            this.name = name;
        }
    }

    /** A data association between an activity and a data object reference. */
    static class DataAssociation {
        final String id;
        final String dataReferenceId;
        final boolean input;        // true = dataInputAssociation (activity consumes)
        final List<double[]> waypoints = new ArrayList<>();

        DataAssociation(String id, String dataReferenceId, boolean input) {
            this.id = id;
            this.dataReferenceId = dataReferenceId;
            this.input = input;
        }
    }
}
