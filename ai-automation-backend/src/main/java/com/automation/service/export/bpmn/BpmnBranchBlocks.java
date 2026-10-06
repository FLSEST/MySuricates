package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The blocks in the branches of a {@code switch} together with their id path — and the notation of the
 * block ids: {@link #member} per block of a chain, {@link #casePrefix}/{@link #defaultPrefix} per
 * branch. Together this yields {@code …_c&lt;k&gt;_&lt;j&gt;} or {@code …_default_&lt;j&gt;},
 * recursively for switch-in-switch.
 *
 * <p>All three producers use the same helpers: {@link BpmnModelBuilder} for the blocks of a
 * chain (top level as well as branch), {@link BpmnGatewayBuilder} for the branch prefixes, and the
 * {@link DataFlowReferenceScanner} rebuilds the same path to attach data associations to the branch task
 * instead of the gateway (7557b928). BPMN allows no data associations on gateways —
 * the branch task is the nearest valid endpoint. If the notation changes, it only has to change
 * here.
 */
final class BpmnBranchBlocks {

    /** A block in a branch, with its id path relative to the top-level block (e.g. {@code _c0_1}). */
    record Branch(String path, BlockDefinition block) {
    }

    private BpmnBranchBlocks() {
    }

    /** Id base of the {@code index}-th block of a chain — for the top-level chain as for a branch. */
    static String member(String chainPrefix, int index) {
        return chainPrefix + "_" + index;
    }

    /** Id prefix of the block chain of a case branch. */
    static String casePrefix(String idBase, int caseIndex) {
        return idBase + "_c" + caseIndex;
    }

    /** Id prefix of the block chain of the default branch. */
    static String defaultPrefix(String idBase) {
        return idBase + "_default";
    }

    /**
     * All blocks in the branches of {@code block}, recursively. Empty for every block that has no
     * nested block list (only {@code switch} has one — {@code conditional} carries
     * commands, not blocks).
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
