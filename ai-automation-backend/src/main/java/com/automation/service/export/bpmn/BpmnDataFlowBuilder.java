package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hängt den erkannten Datenfluss ({@link DataFlowReferenceScanner}) als Datenobjekte und
 * Datenassoziationen ans fertige Modell — inklusive Diagramm-Koordinaten.
 *
 * <p>Je erzeugendem Block entsteht ein Datenobjekt unterhalb seines Tasks; der erzeugende Task
 * bekommt eine {@code dataOutputAssociation}, jeder konsumierende eine {@code dataInputAssociation}.
 * Blöcke ohne einzelnen Aktivitäts-Knoten (conditional/switch werden zu Gateways, an denen BPMN
 * keine Datenassoziationen erlaubt) bleiben außen vor.
 */
final class BpmnDataFlowBuilder {

    /** BPMN-Standardmaß eines Datenobjekts. */
    static final double DATA_W = 36, DATA_H = 50;
    /** Abstand zwischen Unterkante der Task-Reihe und Oberkante der Datenobjekt-Reihe. */
    static final double DATA_ROW_GAP = 60;

    private BpmnDataFlowBuilder() {
    }

    /**
     * @param activityIdByBlockIndex Aktivitäts-Knoten je Top-Level-Blockindex (nur Blöcke, die zu
     *                               genau einem Task/Sub-Prozess werden)
     */
    static void apply(BpmnModel model, List<BlockDefinition> blocks, Map<Integer, String> activityIdByBlockIndex) {
        List<DataFlowReferenceScanner.Reference> refs = DataFlowReferenceScanner.scan(blocks);
        if (refs.isEmpty()) {
            return;
        }
        Map<String, BpmnModel.Node> byId = new LinkedHashMap<>();
        model.nodes.forEach(n -> byId.put(n.id, n));

        double rowY = taskRowBottom(model) + DATA_ROW_GAP;
        Set<String> usedIds = new HashSet<>(byId.keySet());
        Map<Integer, BpmnModel.DataObject> objects = new LinkedHashMap<>();
        int outputs = 0;
        int inputs = 0;

        for (DataFlowReferenceScanner.Reference ref : refs) {
            BpmnModel.Node producer = byId.get(activityIdByBlockIndex.get(ref.sourceIndex()));
            BpmnModel.Node consumer = byId.get(activityIdByBlockIndex.get(ref.targetIndex()));
            if (producer == null || consumer == null) {
                continue; // Gateway-Block (conditional/switch) an einem der beiden Enden
            }
            BpmnModel.DataObject data = objects.get(ref.sourceIndex());
            if (data == null) {
                data = createObject(model, usedIds, producer, blocks.get(ref.sourceIndex()), rowY);
                objects.put(ref.sourceIndex(), data);
                addAssociation(model, producer, data, false, "dataflow_out_" + outputs++);
            }
            addAssociation(model, consumer, data, true, "dataflow_in_" + inputs++);
            if (consumer.dataPropertyId == null) {
                consumer.dataPropertyId = consumer.id + "_data_input";
            }
        }
    }

    private static BpmnModel.DataObject createObject(BpmnModel model, Set<String> usedIds,
                                                     BpmnModel.Node producer, BlockDefinition block, double rowY) {
        String label = block.getName() != null && !block.getName().isBlank()
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
            // Datenobjekt (rechte Kante) -> Unterkante des konsumierenden Tasks
            a.waypoints.add(new double[]{data.x + data.w, data.y + data.h / 2});
            a.waypoints.add(new double[]{activity.x + activity.w / 2, activity.y + activity.h});
            activity.dataInputs.add(a);
        } else {
            // Unterkante des erzeugenden Tasks -> Oberkante des Datenobjekts
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
