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
import static com.automation.service.export.bpmn.BpmnModel.Kind.EXCLUSIVE_GATEWAY;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SCRIPT_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SERVICE_TASK;
import static com.automation.service.export.bpmn.BpmnModel.Kind.START_EVENT;
import static com.automation.service.export.bpmn.BpmnModel.Kind.SUB_PROCESS;
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
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "conditional" -> buildConditional(block, idBase);
            case "switch" -> buildSwitch(block, idBase);
            case "foreach", "while" -> buildLoop(block, idBase);
            default -> singleTask(block, idBase);
        };
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
        n.blockType = type;
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

    private Segment buildConditional(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean shellMode = "shell".equals(str(c, "execution_mode"));

        BpmnModel.Node split = node(idBase + "_split", EXCLUSIVE_GATEWAY, str(c, "condition"), GW, GW);
        split.configJson = toJson(c);
        split.blockType = "conditional";
        BpmnModel.Node merge = node(idBase + "_merge", EXCLUSIVE_GATEWAY, null, GW, GW);
        place(split, 0, 0);
        place(merge, GW + GAP + TASK_W + GAP, 0);

        Segment seg = new Segment();
        seg.nodes.add(split);

        BpmnModel.Node thenTask = commandTask(idBase + "_then", str(c, "then_command"), shellMode);
        place(thenTask, GW + GAP, ROW_STEP);
        seg.nodes.add(thenTask);
        seg.edges.add(edge(split.id, thenTask.id, "ja"));
        seg.edges.add(edge(thenTask.id, merge.id, null));

        String elseCommand = str(c, "else_command");
        if (elseCommand.isBlank()) {
            BpmnModel.Edge direct = edge(split.id, merge.id, "nein");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            BpmnModel.Node elseTask = commandTask(idBase + "_else", elseCommand, shellMode);
            place(elseTask, GW + GAP, 2 * ROW_STEP);
            seg.nodes.add(elseTask);
            BpmnModel.Edge elseEdge = edge(split.id, elseTask.id, "nein");
            seg.edges.add(elseEdge);
            seg.edges.add(edge(elseTask.id, merge.id, null));
            split.defaultFlowId = elseEdge.id;
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = GW + GAP + TASK_W + GAP + GW;
        return seg;
    }

    private Segment buildSwitch(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();

        BpmnModel.Node split = node(idBase + "_split", EXCLUSIVE_GATEWAY, str(c, "expression"), GW, GW);
        split.configJson = toJson(c);
        split.blockType = "switch";
        BpmnModel.Node merge = node(idBase + "_merge", EXCLUSIVE_GATEWAY, null, GW, GW);
        place(split, 0, 0);

        Segment seg = new Segment();
        seg.nodes.add(split);

        List<Map<String, Object>> cases = listOfMaps(c.get("cases"));
        List<Segment> rows = new ArrayList<>();
        List<String> rowNames = new ArrayList<>();
        double maxChainWidth = TASK_W;
        for (int k = 0; k < cases.size(); k++) {
            Segment row = buildChain(toBlockDefinitions(cases.get(k).get("blocks")), idBase + "_c" + k);
            rows.add(row);
            rowNames.add(str(cases.get(k), "value").isBlank() ? "case" + k : str(cases.get(k), "value"));
            maxChainWidth = Math.max(maxChainWidth, row.width);
        }
        List<BlockDefinition> defaultBlocks = toBlockDefinitions(c.get("default"));
        Segment defaultRow = defaultBlocks.isEmpty() ? null : buildChain(defaultBlocks, idBase + "_default");
        if (defaultRow != null) {
            maxChainWidth = Math.max(maxChainWidth, defaultRow.width);
        }

        double mergeX = GW + GAP + maxChainWidth + GAP;
        place(merge, mergeX, 0);

        double nextRowAxis = ROW_STEP;
        for (int k = 0; k < rows.size(); k++) {
            nextRowAxis = attachRow(seg, split, merge, rows.get(k), rowNames.get(k), nextRowAxis, false);
        }
        if (defaultRow == null) {
            BpmnModel.Edge direct = edge(split.id, merge.id, "default");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            nextRowAxis = attachRow(seg, split, merge, defaultRow, "default", nextRowAxis, true);
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = mergeX + GW;
        return seg;
    }

    /**
     * Hängt eine Case-Zeile unterhalb der Hauptlinie ein; leere Zeilen werden zur Direktkante
     * (kein Achsen-Verbrauch). Höhenbewusst: die nächste Zeilenachse berücksichtigt die reale
     * vertikale Ausdehnung der Zeile (z. B. verschachtelte conditional-Blöcke oder SubProcesse),
     * damit sich Zeilen nicht überlappen (Finding 2 des Branch-Reviews).
     */
    private double attachRow(Segment seg, BpmnModel.Node split, BpmnModel.Node merge,
                          Segment row, String label, double rowAxis, boolean isDefault) {
        if (row.entryId == null) {
            BpmnModel.Edge direct = edge(split.id, merge.id, label);
            seg.edges.add(direct);
            if (isDefault) {
                split.defaultFlowId = direct.id;
            }
            return rowAxis;
        }
        // Ausdehnung VOR dem Verschieben ermitteln: bei y=0 (eigene Achse) ist n.y + n.h
        // exakt die Ausdehnung unterhalb/um die Zeilenachse.
        double extentBelow = maxYExtentBelow(row);
        row.translate(GW + GAP, rowAxis);
        seg.nodes.addAll(row.nodes);
        seg.edges.addAll(row.edges);
        BpmnModel.Edge in = edge(split.id, row.entryId, label);
        seg.edges.add(in);
        seg.edges.add(edge(row.exitId, merge.id, null));
        if (isDefault) {
            split.defaultFlowId = in.id;
        }
        return rowAxis + Math.max(ROW_STEP, extentBelow + SUB_PAD * 2); // Puffer >= SubProcess-Halbhöhe (SUB_PAD*2), sonst 5px-Überlappung bei gestapelten Loop-Rows
    }

    /** Vertikale Ausdehnung einer Zeile relativ zu ihrer eigenen Achse (y=0), inkl. Kindknoten. */
    private static double maxYExtentBelow(Segment seg) {
        return maxYExtent(seg.nodes);
    }

    private static double maxYExtent(List<BpmnModel.Node> nodes) {
        double max = 0;
        for (BpmnModel.Node n : nodes) {
            max = Math.max(max, n.y + n.h);
            max = Math.max(max, maxYExtent(n.children));
        }
        return max;
    }

    private Segment buildLoop(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean foreach = "foreach".equals(block.getType());
        boolean shellMode = "shell".equals(str(c, "execution_mode"));

        double innerWidth = EVT + GAP + TASK_W + GAP + EVT;
        double subW = innerWidth + 2 * SUB_PAD;
        double subH = TASK_H + 2 * SUB_PAD;

        String label = block.getName() != null && !block.getName().isBlank()
                ? block.getName()
                : (foreach ? "Für jedes " + str(c, "variable_name") : "Schleife bis Bedingung");

        BpmnModel.Node sub = node(idBase + "_loop", BpmnModel.Kind.SUB_PROCESS, label, subW, subH);
        sub.loop = foreach ? "multiInstanceSequential" : "standardLoop";
        sub.documentation = documentationFor(block);
        sub.configJson = toJson(c);
        sub.blockType = block.getType();
        place(sub, 0, 0);

        BpmnModel.Node innerStart = node(idBase + "_start", BpmnModel.Kind.START_EVENT, null, EVT, EVT);
        BpmnModel.Node body = commandTask(idBase + "_body", str(c, "command"), shellMode);
        BpmnModel.Node innerEnd = node(idBase + "_end", BpmnModel.Kind.END_EVENT, null, EVT, EVT);
        place(innerStart, SUB_PAD, 0);
        place(body, SUB_PAD + EVT + GAP, 0);
        place(innerEnd, SUB_PAD + EVT + GAP + TASK_W + GAP, 0);

        sub.children.add(innerStart);
        sub.children.add(body);
        sub.children.add(innerEnd);
        sub.childEdges.add(edge(innerStart.id, body.id, null));
        sub.childEdges.add(edge(body.id, innerEnd.id, null));

        Segment seg = new Segment();
        seg.nodes.add(sub);
        seg.entryId = sub.id;
        seg.exitId = sub.id;
        seg.width = subW;
        return seg;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /** Verschachtelte Block-Maps (switch cases[].blocks / default) -> BlockDefinitions. */
    @SuppressWarnings("unchecked")
    private static List<BlockDefinition> toBlockDefinitions(Object value) {
        List<BlockDefinition> result = new ArrayList<>();
        for (Map<String, Object> map : listOfMaps(value)) {
            BlockDefinition b = new BlockDefinition();
            b.setType((String) map.get("type"));
            b.setName((String) map.get("name"));
            Object config = map.get("config");
            b.setConfig(config instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>());
            result.add(b);
        }
        return result;
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
