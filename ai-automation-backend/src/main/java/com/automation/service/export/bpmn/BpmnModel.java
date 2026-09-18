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
    /** Datenfluss: je erzeugendem Block ein Datenobjekt (Reihenfolge = Blockreihenfolge). */
    final List<DataObject> dataObjects = new ArrayList<>();
    /** Alle Datenassoziationen in Renderreihenfolge — nur für die Diagramm-Kanten (DI). */
    final List<DataAssociation> dataAssociations = new ArrayList<>();

    static class Node {
        final String id;
        final Kind kind;
        String name;
        String documentation;
        String configJson;
        String blockType;       // Original-Block-Typ (z. B. "shell", "conditional") für verlustfreien Reimport
        String blockName;       // Original-Block-Name (nur wenn gesetzt) für verlustfreien Reimport
        String script;          // nur SCRIPT_TASK
        String scriptFormat;    // nur SCRIPT_TASK: null = bash
        String calledElement;   // nur CALL_ACTIVITY
        String loop;            // null | "multiInstanceSequential" | "standardLoop"
        String defaultFlowId;   // nur EXCLUSIVE_GATEWAY mit Default-Pfad
        String warning;         // erzeugt XML-Kommentar (unbekannter Blocktyp)
        final List<Node> children = new ArrayList<>();      // nur SUB_PROCESS
        final List<Edge> childEdges = new ArrayList<>();    // nur SUB_PROCESS
        final List<DataAssociation> dataInputs = new ArrayList<>();
        final List<DataAssociation> dataOutputs = new ArrayList<>();
        String dataPropertyId;  // Ziel-Platzhalter der dataInputAssociation (nur wenn dataInputs nicht leer)
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

    /** Ausgabe eines Blocks als BPMN-Datenobjekt ({@code dataObject} + {@code dataObjectReference}). */
    static class DataObject {
        final String id;            // dataObject
        final String referenceId;   // dataObjectReference (Ziel/Quelle der Assoziationen)
        final String name;
        double x, y, w, h;

        DataObject(String id, String referenceId, String name) {
            this.id = id;
            this.referenceId = referenceId;
            this.name = name;
        }
    }

    /** Eine Datenassoziation zwischen einer Aktivität und einer Datenobjekt-Referenz. */
    static class DataAssociation {
        final String id;
        final String dataReferenceId;
        final boolean input;        // true = dataInputAssociation (Aktivität konsumiert)
        final List<double[]> waypoints = new ArrayList<>();

        DataAssociation(String id, String dataReferenceId, boolean input) {
            this.id = id;
            this.dataReferenceId = dataReferenceId;
            this.input = input;
        }
    }
}
