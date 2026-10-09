package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnBlockText.listOfMaps;
import static com.automation.service.export.bpmn.BpmnBlockText.str;
import static com.automation.service.export.bpmn.BpmnBlockText.toBlockDefinitions;
import static com.automation.service.export.bpmn.BpmnModel.Kind.EXCLUSIVE_GATEWAY;
import static com.automation.service.export.bpmn.BpmnModelBuilder.*;

/** conditional/switch → split gateway, rows, merge gateway (layout rules from Spec §5). */
final class BpmnGatewayBuilder {

    /** Callback into the chain builder for nested case bodies (switch cases[].blocks / default). */
    interface ChainBuilder {
        BpmnSegment buildChain(List<BlockDefinition> blocks, String idPrefix);

        /**
         * Registers a node as an endpoint for data associations.
         *
         * <p>Blocks do that themselves when being built. The branch tasks of a {@code conditional} are
         * not blocks, though, but commands from the config ({@code then_command}/{@code else_command})
         * — without this registration a reference from a branch ends up at the conditional block
         * itself, i.e. at the gateway, and BPMN allows no data association there: it was discarded
         * (TODO 7557b928).
         */
        void registerActivity(String idBase, String nodeId);
    }

    private final BpmnNodeFactory f;
    private final ChainBuilder chains;

    BpmnGatewayBuilder(BpmnNodeFactory factory, ChainBuilder chains) {
        this.f = factory;
        this.chains = chains;
    }

    BpmnSegment buildConditional(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();
        boolean shellMode = "shell".equals(str(c, "execution_mode"));

        BpmnModel.Node split = f.node(idBase + "_split", EXCLUSIVE_GATEWAY, str(c, "condition"), GW, GW);
        split.configJson = f.toJson(c);
        split.blockType = "conditional";
        split.blockName = block.getName();
        BpmnModel.Node merge = f.node(idBase + "_merge", EXCLUSIVE_GATEWAY, null, GW, GW);
        f.place(split, 0, 0);
        f.place(merge, GW + GAP + TASK_W + GAP, 0);

        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(split);

        BpmnModel.Node thenTask = f.commandTask(idBase + "_then", str(c, "then_command"), shellMode);
        f.place(thenTask, GW + GAP, ROW_STEP);
        seg.nodes.add(thenTask);
        chains.registerActivity(idBase + "_then", thenTask.id);
        seg.edges.add(f.edge(split.id, thenTask.id, "ja"));
        seg.edges.add(f.edge(thenTask.id, merge.id, null));

        String elseCommand = str(c, "else_command");
        if (elseCommand.isBlank()) {
            BpmnModel.Edge direct = f.edge(split.id, merge.id, "nein");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            BpmnModel.Node elseTask = f.commandTask(idBase + "_else", elseCommand, shellMode);
            f.place(elseTask, GW + GAP, 2 * ROW_STEP);
            seg.nodes.add(elseTask);
            chains.registerActivity(idBase + "_else", elseTask.id);
            BpmnModel.Edge elseEdge = f.edge(split.id, elseTask.id, "nein");
            seg.edges.add(elseEdge);
            seg.edges.add(f.edge(elseTask.id, merge.id, null));
            split.defaultFlowId = elseEdge.id;
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = GW + GAP + TASK_W + GAP + GW;
        return seg;
    }

    BpmnSegment buildSwitch(BlockDefinition block, String idBase) {
        Map<String, Object> c = block.getConfig();

        BpmnModel.Node split = f.node(idBase + "_split", EXCLUSIVE_GATEWAY, str(c, "expression"), GW, GW);
        split.configJson = f.toJson(c);
        split.blockType = "switch";
        split.blockName = block.getName();
        BpmnModel.Node merge = f.node(idBase + "_merge", EXCLUSIVE_GATEWAY, null, GW, GW);
        f.place(split, 0, 0);

        BpmnSegment seg = new BpmnSegment();
        seg.nodes.add(split);

        List<Map<String, Object>> cases = listOfMaps(c.get("cases"));
        List<BpmnSegment> rows = new ArrayList<>();
        List<String> rowNames = new ArrayList<>();
        double maxChainWidth = TASK_W;
        for (int k = 0; k < cases.size(); k++) {
            BpmnSegment row = chains.buildChain(toBlockDefinitions(cases.get(k).get("blocks")),
                    BpmnBranchBlocks.casePrefix(idBase, k));
            stripDerivedBlockConfig(row.nodes);
            rows.add(row);
            rowNames.add(str(cases.get(k), "value").isBlank() ? "case" + k : str(cases.get(k), "value"));
            maxChainWidth = Math.max(maxChainWidth, row.width);
        }
        List<BlockDefinition> defaultBlocks = toBlockDefinitions(c.get("default"));
        BpmnSegment defaultRow = defaultBlocks.isEmpty()
                ? null
                : chains.buildChain(defaultBlocks, BpmnBranchBlocks.defaultPrefix(idBase));
        if (defaultRow != null) {
            stripDerivedBlockConfig(defaultRow.nodes);
            maxChainWidth = Math.max(maxChainWidth, defaultRow.width);
        }

        double mergeX = GW + GAP + maxChainWidth + GAP;
        f.place(merge, mergeX, 0);

        double nextRowAxis = ROW_STEP;
        for (int k = 0; k < rows.size(); k++) {
            nextRowAxis = attachRow(seg, split, merge, rows.get(k), rowNames.get(k), nextRowAxis, false);
        }
        if (defaultRow == null) {
            BpmnModel.Edge direct = f.edge(split.id, merge.id, "default");
            seg.edges.add(direct);
            split.defaultFlowId = direct.id;
        } else {
            attachRow(seg, split, merge, defaultRow, "default", nextRowAxis, true);
        }

        seg.nodes.add(merge);
        seg.entryId = split.id;
        seg.exitId = merge.id;
        seg.width = mergeX + GW;
        return seg;
    }

    /**
     * Hangs a case row below the main line; empty rows become the direct edge
     * (no axis consumption). Height-aware: the next row axis takes the real
     * vertical extent of the row into account (e.g. nested conditional blocks or sub-processes),
     * so that rows do not overlap (finding 2 of the branch review).
     */
    private double attachRow(BpmnSegment seg, BpmnModel.Node split, BpmnModel.Node merge,
                          BpmnSegment row, String label, double rowAxis, boolean isDefault) {
        if (row.entryId == null) {
            BpmnModel.Edge direct = f.edge(split.id, merge.id, label);
            seg.edges.add(direct);
            if (isDefault) {
                split.defaultFlowId = direct.id;
            }
            return rowAxis;
        }
        // Determine the extent BEFORE shifting: at y=0 (own axis) n.y + n.h
        // is exactly the extent below/around the row axis.
        double extentBelow = maxYExtentBelow(row);
        row.translate(GW + GAP, rowAxis);
        seg.nodes.addAll(row.nodes);
        seg.edges.addAll(row.edges);
        BpmnModel.Edge in = f.edge(split.id, row.entryId, label);
        seg.edges.add(in);
        seg.edges.add(f.edge(row.exitId, merge.id, null));
        if (isDefault) {
            split.defaultFlowId = in.id;
        }
        return rowAxis + Math.max(ROW_STEP, extentBelow + SUB_PAD * 2); // buffer >= sub-process half height (SUB_PAD*2), otherwise 5px overlap with stacked loop rows
    }

    /**
     * Removes blockConfig/type/name from derived nodes (switch case/default contents).
     * Their config is already in the config JSON of the switch block — a blockConfig of their own
     * would make the reimport count twice. Purely visual nodes thus stay config-free.
     */
    private static void stripDerivedBlockConfig(List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            n.configJson = null;
            n.blockType = null;
            n.blockName = null;
            stripDerivedBlockConfig(n.children);
        }
    }

    /** Vertical extent of a row relative to its own axis (y=0), including child nodes. */
    private static double maxYExtentBelow(BpmnSegment seg) {
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
}
