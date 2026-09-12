package com.automation.service.export.bpmn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnModel.Kind.SCRIPT_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SERVICE_TASK;
import static com.automation.service.export.bpmn.BpmnModelBuilder.TASK_H;
import static com.automation.service.export.bpmn.BpmnModelBuilder.TASK_W;

/** Creates nodes and edges with unique ids; one instance per build. */
final class BpmnNodeFactory {

    private final ObjectMapper objectMapper;
    private final Map<String, BpmnModel.Node> nodeIndex = new HashMap<>();
    private int flowCounter = 0;

    BpmnNodeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, BpmnModel.Node> nodeIndex() {
        return Collections.unmodifiableMap(nodeIndex);
    }

    BpmnModel.Node node(String id, BpmnModel.Kind kind, String name, double w, double h) {
        BpmnModel.Node n = new BpmnModel.Node(uniqueId(id), kind);
        n.name = name;
        n.w = w;
        n.h = h;
        nodeIndex.put(n.id, n);
        return n;
    }

    private String uniqueId(String id) {
        String candidate = id;
        int suffix = 2;
        while (nodeIndex.containsKey(candidate)) {
            candidate = id + "_" + suffix++;
        }
        return candidate;
    }

    /** Setzt x und zentriert den Knoten vertikal auf rowCenterY. */
    void place(BpmnModel.Node n, double x, double rowCenterY) {
        n.x = x;
        n.y = rowCenterY - n.h / 2;
    }

    BpmnModel.Edge edge(String sourceRef, String targetRef, String name) {
        BpmnModel.Edge e = new BpmnModel.Edge("flow_" + (flowCounter++), sourceRef, targetRef);
        e.name = name;
        return e;
    }

    /** Task für einen flachen Command-String (conditional-Zweige, Schleifen-Body). */
    BpmnModel.Node commandTask(String id, String command, boolean shellMode) {
        BpmnModel.Node n = node(id, shellMode ? SCRIPT_TASK : SERVICE_TASK, null, TASK_W, TASK_H);
        n.name = BpmnNames.taskName(null, shellMode ? "Shell" : "KI", command);
        n.documentation = BpmnBlockText.firstLine(command, 200);
        if (shellMode) {
            n.script = command;
        }
        return n;
    }

    String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
