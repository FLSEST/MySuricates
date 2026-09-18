package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Die Blöcke in den Zweigen eines {@code switch} samt ihrem Id-Pfad — und die Schreibweise der
 * Block-Ids: {@link #member} je Block einer Kette, {@link #casePrefix}/{@link #defaultPrefix} je
 * Zweig. Zusammen ergibt das {@code …_c&lt;k&gt;_&lt;j&gt;} bzw. {@code …_default_&lt;j&gt;},
 * rekursiv bei Switch-in-Switch.
 *
 * <p>Alle drei Erzeuger nutzen dieselben Helfer: {@link BpmnModelBuilder} für die Blöcke einer
 * Kette (Top-Level wie Zweig), {@link BpmnGatewayBuilder} für die Zweig-Präfixe, und der
 * {@link DataFlowReferenceScanner} baut denselben Pfad nach, um Datenassoziationen an den Zweig-Task
 * statt an das Gateway zu hängen (7557b928). BPMN erlaubt an Gateways keine Datenassoziationen —
 * der Zweig-Task ist der nächstgelegene gültige Endpunkt. Ändert sich die Schreibweise, muss sie
 * sich nur hier ändern.
 */
final class BpmnBranchBlocks {

    /** Ein Block in einem Zweig, mit seinem Id-Pfad relativ zum Top-Level-Block (z. B. {@code _c0_1}). */
    record Branch(String path, BlockDefinition block) {
    }

    private BpmnBranchBlocks() {
    }

    /** Id-Basis des {@code index}-ten Blocks einer Kette — für die Top-Level-Kette wie für einen Zweig. */
    static String member(String chainPrefix, int index) {
        return chainPrefix + "_" + index;
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
            String path = member(prefix, j);
            out.add(new Branch(path, blocks.get(j)));
            collect(blocks.get(j), path, out, depth + 1);
        }
    }
}
