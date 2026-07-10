package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnModel.Kind.CALL_ACTIVITY;
import static com.automation.service.export.bpmn.BpmnModel.Kind.END_EVENT;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SCRIPT_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SERVICE_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.START_EVENT;
import static com.automation.service.export.bpmn.BpmnModel.Kind.TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.USER_TASK;

/**
 * Baut aus einem Workflow das interne BPMN-Modell inkl. Koordinaten.
 * Deterministisch, kein AI-Call (Spec §3). Eine Instanz pro build()-Aufruf.
 */
class BpmnModelBuilder {

    // Layout-Konstanten (Spec §5)
    static final double TASK_W = 100, TASK_H = 80, EVT = 36, GW = 50;
    static final double GAP = 150, MAIN_Y = 200, ROW_STEP = 150, SUB_PAD = 40;

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("simple", "KI"), Map.entry("shell", "Shell"), Map.entry("agentic", "Agent"),
            Map.entry("skill-capability", "Skill"), Map.entry("integration", "Integration"),
            Map.entry("document", "Dokument"), Map.entry("approval", "Freigabe"),
            Map.entry("human-input", "Eingabe"), Map.entry("sub-workflow", "Sub-Workflow"),
            Map.entry("retry", "Retry"));

    private final ObjectMapper objectMapper;
    private final Map<String, BpmnModel.Node> nodeIndex = new HashMap<>();
    private int flowCounter = 0;

    BpmnModelBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    BpmnModel build(Workflow workflow) {
        BpmnModel model = new BpmnModel();
        model.processName = workflow.getName() == null || workflow.getName().isBlank() ? "Workflow" : workflow.getName();
        model.processId = BpmnNames.elementId("process", model.processName);

        BpmnModel.Node start = node("start_event", START_EVENT, "Start", EVT, EVT);
        BpmnModel.Node end = node("end_event", END_EVENT, "Ende", EVT, EVT);
        place(start, 0, 0);

        List<BlockDefinition> blocks = workflow.getBlocks() == null ? List.of() : workflow.getBlocks();
        Segment chain = buildChain(blocks, "b");
        chain.translate(EVT + GAP, 0);

        double endX = chain.entryId == null ? EVT + GAP : EVT + GAP + chain.width + GAP;
        place(end, endX, 0);

        model.nodes.add(start);
        model.nodes.addAll(chain.nodes);
        model.nodes.add(end);
        model.edges.addAll(chain.edges);
        if (chain.entryId == null) {
            model.edges.add(edge(start.id, end.id, null));
        } else {
            model.edges.add(edge(start.id, chain.entryId, null));
            model.edges.add(edge(chain.exitId, end.id, null));
        }

        Segment.translateNodes(model.nodes, 100, MAIN_Y);
        model.edges.forEach(this::route);
        routeChildEdges(model.nodes);
        return model;
    }

    /** Horizontale Kette aus Blöcken; Koordinaten relativ (Zeilenmitte y=0, Beginn x=0). */
    private Segment buildChain(List<BlockDefinition> blocks, String idPrefix) {
        Segment seg = new Segment();
        double cursor = 0;
        String prevExit = null;
        for (int i = 0; i < blocks.size(); i++) {
            Segment part = buildBlock(blocks.get(i), idPrefix + "_" + i);
            part.translate(cursor, 0);
            seg.nodes.addAll(part.nodes);
            seg.edges.addAll(part.edges);
            if (seg.entryId == null) {
                seg.entryId = part.entryId;
            }
            if (prevExit != null) {
                seg.edges.add(edge(prevExit, part.entryId, null));
            }
            prevExit = part.exitId;
            cursor += part.width + GAP;
        }
        seg.exitId = prevExit;
        seg.width = blocks.isEmpty() ? 0 : cursor - GAP;
        return seg;
    }

    private Segment buildBlock(BlockDefinition block, String idBase) {
        // Task 3 ergänzt: case "conditional" / "switch"; Task 4 ergänzt: case "foreach" / "while"
        return singleTask(block, idBase);
    }

    /** Einzelner Block -> ein Task-Knoten. */
    private Segment singleTask(BlockDefinition block, String idBase) {
        String type = block.getType() == null ? "" : block.getType();
        Map<String, Object> config = block.getConfig();
        BpmnModel.Kind kind = switch (type) {
            case "shell" -> SCRIPT_TASK;
            case "simple", "agentic", "skill-capability", "integration", "document" -> SERVICE_TASK;
            case "approval", "human-input" -> USER_TASK;
            case "sub-workflow" -> CALL_ACTIVITY;
            case "retry" -> "shell".equals(str(config, "execution_mode")) ? SCRIPT_TASK : SERVICE_TASK;
            default -> TASK;
        };
        String label = TYPE_LABELS.getOrDefault(type, type.isBlank() ? "Task" : type);
        String name = BpmnNames.taskName(block.getName(), label, nameSource(block));
        if ("retry".equals(type)) {
            name = name + " (bis zu " + intVal(config, "max_retries", 3) + " Versuche)";
        }

        BpmnModel.Node n = node(BpmnNames.elementId(idBase, name), kind, name, TASK_W, TASK_H);
        n.documentation = documentationFor(block);
        n.configJson = toJson(config);
        if (kind == SCRIPT_TASK) {
            n.script = str(config, "command");
        }
        if (kind == CALL_ACTIVITY) {
            n.calledElement = str(config, "workflow_id");
        }
        if (kind == TASK) {
            n.warning = "Unbekannter Block-Typ: " + type;
        }
        place(n, 0, 0);

        Segment seg = new Segment();
        seg.nodes.add(n);
        seg.entryId = n.id;
        seg.exitId = n.id;
        seg.width = TASK_W;
        return seg;
    }

    /** Task für einen flachen Command-String (conditional-Zweige, Schleifen-Body). */
    BpmnModel.Node commandTask(String id, String command, boolean shellMode) {
        BpmnModel.Node n = node(id, shellMode ? SCRIPT_TASK : SERVICE_TASK, null, TASK_W, TASK_H);
        n.name = BpmnNames.taskName(null, shellMode ? "Shell" : "KI", command);
        n.documentation = firstLine(command, 200);
        if (shellMode) {
            n.script = command;
        }
        return n;
    }

    private String nameSource(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> str(c, "goal");
            case "approval" -> str(c, "question");
            case "human-input" -> str(c, "prompt");
            case "integration" -> joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> str(c, "file_path");
            case "skill-capability" -> str(c, "capability");
            default -> str(c, "command");
        };
    }

    private String documentationFor(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> "Ziel: " + str(c, "goal");
            case "approval" -> "Freigabefrage: " + str(c, "question");
            case "human-input" -> "Eingabe: " + str(c, "prompt");
            case "integration" -> "Integration: " + joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> "Dokument: " + str(c, "file_path");
            case "skill-capability" -> "Skill-Capability: " + str(c, "capability");
            case "while" -> "Wiederholen solange: " + firstLine(str(c, "condition_command"), 200);
            case "foreach" -> "Iteriert über: " + str(c, "variable_name");
            case "retry" -> "Automatische Wiederholung bei Fehler";
            default -> firstLine(str(c, "command"), 200);
        };
    }

    // ---- Basis-Helfer ----

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

    private String toJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    static String str(Map<String, Object> c, String key) {
        Object v = c == null ? null : c.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static int intVal(Map<String, Object> c, String key, int fallback) {
        Object v = c == null ? null : c.get(key);
        return v instanceof Number number ? number.intValue() : fallback;
    }

    static String firstLine(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() > max ? line.substring(0, max - 3) + "..." : line;
    }

    static String joinNonBlank(String left, String right) {
        if (left.isBlank()) {
            return right;
        }
        return right.isBlank() ? left : left + "." + right;
    }

    // ---- Kanten-Routing (rechtwinklig, Spec §5) ----

    private void routeChildEdges(List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            n.childEdges.forEach(this::route);
            routeChildEdges(n.children);
        }
    }

    private void route(BpmnModel.Edge e) {
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

    /** Teil-Graph mit relativen Koordinaten; wird beim Einhängen verschoben. */
    static class Segment {
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
}
