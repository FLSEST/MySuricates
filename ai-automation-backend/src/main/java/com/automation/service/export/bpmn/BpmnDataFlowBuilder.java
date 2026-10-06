package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Attaches the detected data flow ({@link DataFlowReferenceScanner}) as data objects and
 * data associations to the finished model — including diagram coordinates.
 *
 * <p>For each producing block a data object is created below its task; the producing task
 * gets a {@code dataOutputAssociation}, every consuming one a {@code dataInputAssociation}.
 * The endpoint is always an activity node — for references from a switch branch the task of the
 * branch block (7557b928), because BPMN allows no data associations on gateways. Blocks without
 * an activity node of their own (the switch/conditional itself) are left out.
 */
final class BpmnDataFlowBuilder {

    /** BPMN standard size of a data object. */
    static final double DATA_W = 36, DATA_H = 50;
    /** Distance between the bottom edge of the task row and the top edge of the data object row. */
    static final double DATA_ROW_GAP = 60;

    private BpmnDataFlowBuilder() {
    }

    /**
     * @param activityIdByBase activity node per id base (top-level blocks <em>and</em>
     *                         branch blocks), as {@code BpmnModelBuilder} assigns them
     * @param rootPrefix       id prefix of the top-level chain (see {@code DataFlowReferenceScanner.Endpoint})
     */
    static void apply(BpmnModel model, List<BlockDefinition> blocks,
                      Map<String, String> activityIdByBase, String rootPrefix) {
        List<DataFlowReferenceScanner.Reference> refs = DataFlowReferenceScanner.scan(blocks);
        if (refs.isEmpty()) {
            return;
        }
        Map<String, BpmnModel.Node> byId = new LinkedHashMap<>();
        model.nodes.forEach(n -> byId.put(n.id, n));

        double rowY = taskRowBottom(model) + DATA_ROW_GAP;
        Set<String> usedIds = new HashSet<>(byId.keySet());
        Map<DataFlowReferenceScanner.Endpoint, BpmnModel.DataObject> objects = new LinkedHashMap<>();
        int outputs = 0;
        int inputs = 0;

        for (DataFlowReferenceScanner.Reference ref : refs) {
            BpmnModel.Node producer = byId.get(activityIdByBase.get(ref.source().idBase(rootPrefix)));
            BpmnModel.Node consumer = byId.get(activityIdByBase.get(ref.target().idBase(rootPrefix)));
            if (producer == null || consumer == null) {
                continue; // gateway node (the switch/conditional itself) at one of the two ends
            }
            BpmnModel.DataObject data = objects.get(ref.source());
            if (data == null) {
                data = createObject(model, usedIds, producer, blockAt(blocks, ref.source()), rowY);
                objects.put(ref.source(), data);
                addAssociation(model, producer, data, false, "dataflow_out_" + outputs++);
            }
            addAssociation(model, consumer, data, true, "dataflow_in_" + inputs++);
            if (consumer.dataPropertyId == null) {
                consumer.dataPropertyId = consumer.id + "_data_input";
            }
        }
    }

    /**
     * The block behind an endpoint — {@code null} for branch blocks: their name is already part of the
     * name of the branch task, and only named branch blocks can be a source at all.
     */
    private static BlockDefinition blockAt(List<BlockDefinition> blocks, DataFlowReferenceScanner.Endpoint endpoint) {
        return endpoint.branchPath().isEmpty() ? blocks.get(endpoint.blockIndex()) : null;
    }

    private static BpmnModel.DataObject createObject(BpmnModel model, Set<String> usedIds,
                                                     BpmnModel.Node producer, BlockDefinition block, double rowY) {
        String label = block != null && block.getName() != null && !block.getName().isBlank()
                ? block.getName().strip()
                : (producer.name == null || producer.name.isBlank() ? producer.id : producer.name);
        String base = BpmnNames.elementId("data", label);
        BpmnModel.DataObject data = new BpmnModel.DataObject(
                unique(usedIds, base), unique(usedIds, "dataref" + base.substring("data".length())),
                "Ausgabe: " + label);
        data.w = DATA_W;
        data.h = DATA_H;
        data.x = producer.x + producer.w / 2 - DATA_W / 2;
        data.y = rowY;
        model.dataObjects.add(data);
        return data;
    }

    private static void addAssociation(BpmnModel model, BpmnModel.Node activity,
                                       BpmnModel.DataObject data, boolean input, String id) {
        BpmnModel.DataAssociation a = new BpmnModel.DataAssociation(id, data.referenceId, input);
        if (input) {
            // data object (right edge) -> bottom edge of the consuming task
            a.waypoints.add(new double[]{data.x + data.w, data.y + data.h / 2});
            a.waypoints.add(new double[]{activity.x + activity.w / 2, activity.y + activity.h});
            activity.dataInputs.add(a);
        } else {
            // bottom edge of the producing task -> top edge of the data object
            a.waypoints.add(new double[]{activity.x + activity.w / 2, activity.y + activity.h});
            a.waypoints.add(new double[]{data.x + data.w / 2, data.y});
            activity.dataOutputs.add(a);
        }
        model.dataAssociations.add(a);
    }

    private static double taskRowBottom(BpmnModel model) {
        double bottom = 0;
        for (BpmnModel.Node n : model.nodes) {
            bottom = Math.max(bottom, n.y + n.h);
        }
        return bottom;
    }

    private static String unique(Set<String> used, String candidate) {
        String id = candidate;
        int suffix = 2;
        while (!used.add(id)) {
            id = candidate + "_" + suffix++;
        }
        return id;
    }
}
