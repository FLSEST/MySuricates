package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;

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
class BpmnModelBuilder implements BpmnGatewayBuilder.ChainBuilder {

    // Layout-Konstanten (Spec §5)
    static final double TASK_W = 100, TASK_H = 80, EVT = 36, GW = 50;
    static final double GAP = 150, MAIN_Y = 200, ROW_STEP = 150, SUB_PAD = 40;

    private final BpmnNodeFactory f;
    private final BpmnGatewayBuilder gateways;

    BpmnModelBuilder(ObjectMapper objectMapper) {
        this.f = new BpmnNodeFactory(objectMapper);
        this.gateways = new BpmnGatewayBuilder(f, this);
    }

    BpmnModel build(Workflow workflow) {
        BpmnModel model = new BpmnModel();
        model.processName = workflow.getName() == null || workflow.getName().isBlank() ? "Workflow" : workflow.getName();
        model.processId = BpmnNames.elementId("process", model.processName);

        BpmnModel.Node start = f.node("start_event", START_EVENT, "Start", EVT, EVT);
        BpmnModel.Node end = f.node("end_event", END_EVENT, "Ende", EVT, EVT);
        f.place(start, 0, 0);

        List<BlockDefinition> blocks = workflow.getBlocks() == null ? List.of() : workflow.getBlocks();
        BpmnSegment chain = buildChain(blocks, "b");
        chain.translate(EVT + GAP, 0);

        double endX = chain.entryId == null ? EVT + GAP : EVT + GAP + chain.width + GAP;
        f.place(end, endX, 0);

        model.nodes.add(start);
        model.nodes.addAll(chain.nodes);
        model.nodes.add(end);
        model.edges.addAll(chain.edges);
        if (chain.entryId == null) {
            model.edges.add(f.edge(start.id, end.id, null));
        } else {
            model.edges.add(f.edge(start.id, chain.entryId, null));
            model.edges.add(f.edge(chain.exitId, end.id, null));
        }

        BpmnSegment.translateNodes(model.nodes, 100, MAIN_Y);
        BpmnEdgeRouter router = new BpmnEdgeRouter(f.nodeIndex());
        model.edges.forEach(router::route);
        router.routeChildEdges(model.nodes);
        return model;
    }

    /** Horizontale Kette aus Blöcken; Koordinaten relativ (Zeilenmitte y=0, Beginn x=0). */
    @Override
    public BpmnSegment buildChain(List<BlockDefinition> blocks, String idPrefix) {
        BpmnSegment seg = new BpmnSegment();
        double cursor = 0;
        String prevExit = null;
        for (int i = 0; i < blocks.size(); i++) {
            BpmnSegment part = buildBlock(blocks.get(i), idPrefix + "_" + i);
            part.translate(cursor, 0);
            seg.nodes.addAll(part.nodes);
            seg.edges.addAll(part.edges);
            if (seg.entryId == null) {
                seg.entryId = part.entryId;
            }
            if (prevExit != null) {
                seg.edges.add(f.edge(prevExit, part.entryId, null));
            }
            prevExit = part.exitId;
            cursor += part.width + GAP;
        }
        seg.exitId = prevExit;
        seg.width = blocks.isEmpty() ? 0 : cursor - GAP;
        return seg;
    }

    private BpmnSegment buildBlock(BlockDefinition block, String idBase) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "conditional" -> gateways.buildConditional(block, idBase);
            case "switch" -> gateways.buildSwitch(block, idBase);
            case "foreach", "while" -> buildLoop(block, idBase);
            default -> singleTask(block, idBase);
        };
    }

    /** Einzelner Block -> ein Task-Knoten. */
    private BpmnSegment singleTask(BlockDefinition block, String idBase) {
        String type = block.getType() == null ? "" : block.getType();
        Map<String, Object> config = block.getConfig();
        BpmnModel.Kind kind = switch (type) {
            case "shell" -> SCRIPT_TASK;
            case "simple", "agentic", "skill-capability", "integration", "document" -> SERVICE_TASK;
            case "approval", "human-input" -> USER_TASK;
            case "sub-workflow" -> CALL_ACTIVITY;
            case "retry" -> "shell".equals(BpmnBlockText.str(config, "execution_mode")) ? SCRIPT_TASK : SERVICE_TASK;
            default -> TASK;
        };
        String label = BpmnBlockText.label(type);
        String name = BpmnNames.taskName(block.getName(), label, BpmnBlockText.nameSource(block));
        if ("retry".equals(type)) {
            name = name + " (bis zu " + BpmnBlockText.intVal(config, "max_retries", 3) + " Versuche)";
        }

        BpmnModel.Node n = f.node(BpmnNames.elementId(idBase, name), kind, name, TASK_W, TASK_H);
        n.documentation = BpmnBlockText.documentationFor(block);
        n.configJson = f.toJson(config);
        n.blockType = type;
        n.blockName = block.getName();
        if (kind == SCRIPT_TASK) {
            n.script = BpmnBlockText.str(config, "command");
        }
        if (kind == CALL_ACTIVITY) {
            n.calledElement = BpmnBlockText.str(config, "workflow_id");
        }
        if (kind == TASK) {
            n.warning = "Unbekannter Block-Typ: " + type;
        }
        f.place(n, 0, 0);

        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(n);
        seg.entryId = n.id;
        seg.exitId = n.id;
        seg.width = TASK_W;
        return seg;
    }

    private BpmnSegment buildLoop(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean foreach = "foreach".equals(block.getType());
        boolean shellMode = "shell".equals(BpmnBlockText.str(c, "execution_mode"));

        double innerWidth = EVT + GAP + TASK_W + GAP + EVT;
        double subW = innerWidth + 2 * SUB_PAD;
        double subH = TASK_H + 2 * SUB_PAD;

        String label = block.getName() != null && !block.getName().isBlank()
                ? block.getName()
                : (foreach ? "Für jedes " + BpmnBlockText.str(c, "variable_name") : "Schleife bis Bedingung");

        BpmnModel.Node sub = f.node(idBase + "_loop", BpmnModel.Kind.SUB_PROCESS, label, subW, subH);
        sub.loop = foreach ? "multiInstanceSequential" : "standardLoop";
        sub.documentation = BpmnBlockText.documentationFor(block);
        sub.configJson = f.toJson(c);
        sub.blockType = block.getType();
        sub.blockName = block.getName();
        f.place(sub, 0, 0);

        BpmnModel.Node innerStart = f.node(idBase + "_start", BpmnModel.Kind.START_EVENT, null, EVT, EVT);
        BpmnModel.Node body = f.commandTask(idBase + "_body", BpmnBlockText.str(c, "command"), shellMode);
        BpmnModel.Node innerEnd = f.node(idBase + "_end", BpmnModel.Kind.END_EVENT, null, EVT, EVT);
        f.place(innerStart, SUB_PAD, 0);
        f.place(body, SUB_PAD + EVT + GAP, 0);
        f.place(innerEnd, SUB_PAD + EVT + GAP + TASK_W + GAP, 0);

        sub.children.add(innerStart);
        sub.children.add(body);
        sub.children.add(innerEnd);
        sub.childEdges.add(f.edge(innerStart.id, body.id, null));
        sub.childEdges.add(f.edge(body.id, innerEnd.id, null));

        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(sub);
        seg.entryId = sub.id;
        seg.exitId = sub.id;
        seg.width = subW;
        return seg;
    }
}
