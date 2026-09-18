package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Die Blöcke in den Zweigen eines {@code switch} samt ihrem Id-Pfad — die einzige Stelle, die
 * weiß, wie Zweig-Ids geschrieben werden ({@code …_c&lt;k&gt;_&lt;j&gt;} je Case-Block,
 * {@code …_default_&lt;j&gt;} je Default-Block, rekursiv bei Switch-in-Switch).
 *
 * <p>{@link BpmnGatewayBuilder} baut die Knoten-Ids über {@link #casePrefix}/{@link #defaultPrefix}
 * (die Kette hängt je Block noch {@code _&lt;j&gt;} an), der {@link DataFlowReferenceScanner} baut
 * denselben Pfad, um Datenassoziationen an den Zweig-Task statt an das Gateway zu hängen (7557b928).
 * BPMN erlaubt an Gateways keine Datenassoziationen — der Zweig-Task ist der nächstgelegene
 * gültige Endpunkt.
 */
final class BpmnBranchBlocks {

    /** Ein Block in einem Zweig, mit seinem Id-Pfad relativ zum Top-Level-Block (z. B. {@code _c0_1}). */
    record Branch(String path, BlockDefinition block) {
    }

    private BpmnBranchBlocks() {
    }

    /** Id-Präfix der Block-Kette eines Case-Zweigs. */
    static String casePrefix(String idBase, int caseIndex) {
        return idBase + "_c" + caseIndex;
    }

    /** Id-Präfix der Block-Kette des Default-Zweigs. */
    static String defaultPrefix(String idBase) {
        return idBase + "_default";
    }

    /**
     * Alle Blöcke in den Zweigen von {@code block}, rekursiv. Leer für jeden Block, der keine
     * verschachtelte Blockliste hat (nur {@code switch} hat eine — {@code conditional} trägt
     * Kommandos, keine Blöcke).
     */
    static List<Branch> of(BlockDefinition block) {
        List<Branch> out = new ArrayList<>();
        collect(block, "", out, 0);
        return List.copyOf(out);
    }

    private static void collect(BlockDefinition block, String path, List<Branch> out, int depth) {
        if (block == null || depth > 10 || !"switch".equals(block.getType()) || block.getConfig() == null) {
            return;
        }
        Map<String, Object> config = block.getConfig();
        List<Map<String, Object>> cases = BpmnBlockText.listOfMaps(config.get("cases"));
        for (int k = 0; k < cases.size(); k++) {
            collectChain(BpmnBlockText.toBlockDefinitions(cases.get(k).get("blocks")),
                    casePrefix(path, k), out, depth);
        }
        collectChain(BpmnBlockText.toBlockDefinitions(config.get("default")),
                defaultPrefix(path), out, depth);
    }

    private static void collectChain(List<BlockDefinition> blocks, String prefix, List<Branch> out, int depth) {
        for (int j = 0; j < blocks.size(); j++) {
            String path = prefix + "_" + j;
            out.add(new Branch(path, blocks.get(j)));
            collect(blocks.get(j), path, out, depth + 1);
        }
    }
}
