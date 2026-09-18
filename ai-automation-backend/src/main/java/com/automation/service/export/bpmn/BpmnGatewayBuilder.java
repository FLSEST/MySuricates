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
        // Ausdehnung VOR dem Verschieben ermitteln: bei y=0 (eigene Achse) ist n.y + n.h
        // exakt die Ausdehnung unterhalb/um die Zeilenachse.
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
        return rowAxis + Math.max(ROW_STEP, extentBelow + SUB_PAD * 2); // Puffer >= SubProcess-Halbhöhe (SUB_PAD*2), sonst 5px-Überlappung bei gestapelten Loop-Rows
    }

    /**
     * Entfernt blockConfig/type/name von abgeleiteten Knoten (Switch-Case-/Default-Inhalte).
     * Deren Config steckt bereits im Config-JSON des Switch-Blocks — ein eigenes blockConfig
     * würde den Reimport doppelt zählen lassen. Rein visuelle Knoten bleiben so config-los.
     */
    private static void stripDerivedBlockConfig(List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            n.configJson = null;
            n.blockType = null;
            n.blockName = null;
            stripDerivedBlockConfig(n.children);
        }
    }

    /** Vertikale Ausdehnung einer Zeile relativ zu ihrer eigenen Achse (y=0), inkl. Kindknoten. */
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
