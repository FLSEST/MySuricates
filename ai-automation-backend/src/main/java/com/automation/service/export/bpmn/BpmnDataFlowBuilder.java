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
 * Endpunkt ist immer ein Aktivitäts-Knoten — für Referenzen aus einem Switch-Zweig der Task des
 * Zweig-Blocks (7557b928), denn an Gateways erlaubt BPMN keine Datenassoziationen. Blöcke ganz ohne
 * eigenen Aktivitäts-Knoten (der Switch/Conditional selbst) bleiben außen vor.
 */
final class BpmnDataFlowBuilder {

    /** BPMN-Standardmaß eines Datenobjekts. */
    static final double DATA_W = 36, DATA_H = 50;
    /** Abstand zwischen Unterkante der Task-Reihe und Oberkante der Datenobjekt-Reihe. */
    static final double DATA_ROW_GAP = 60;

    private BpmnDataFlowBuilder() {
    }

    /**
     * @param activityIdByBase Aktivitäts-Knoten je Id-Basis (Top-Level-Blöcke <em>und</em>
     *                         Zweig-Blöcke), wie {@code BpmnModelBuilder} sie vergibt
     * @param rootPrefix       Id-Präfix der Top-Level-Kette (siehe {@code DataFlowReferenceScanner.Endpoint})
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
                continue; // Gateway-Knoten (der switch/conditional selbst) an einem der beiden Enden
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
     * Der Block hinter einem Endpunkt — {@code null} für Zweig-Blöcke: deren Name steckt bereits im
     * Namen des Zweig-Tasks, und nur benannte Zweig-Blöcke können überhaupt Quelle sein.
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
