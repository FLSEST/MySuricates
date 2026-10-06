package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.entity.TriggerSource;
import com.automation.executor.PlaceholderSyntax;
import com.automation.service.audit.AuditFindingsContext;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules for the <b>wiring</b> of a definition: is generated output also read, and does
 * every reference point to something that exists?
 *
 * <p>The trigger was PRD-07 (TODO 45509fcb): a shell block delivered 31 KB that no following block
 * referenced, and three of four blocks carried a placeholder that is never set outside a chain
 * and therefore arrived as a literal in the prompt.
 */
final class WiringRules {

    private WiringRules() {}

    /**
     * Only set inside a chain — in a workflow running on its own it stays a literal. Exactly the
     * keys that {@code ChainExecutor} sets for every entry; {@code chain_step_output} used to be here although
     * nothing sets it, not even a chain — the message was misleading there (TODO 6db47bc2).
     */
    static final Set<String> CHAIN_ONLY = Set.of("previous_chain_output", TriggerSource.CHAIN_RUN_CONTEXT_KEY);

    /** Only set in an audit workflow (valid {@code metadata.audit}) — otherwise it stays a literal. */
    private static final String AUDIT_ONLY = "audit_findings";

    /** @param metadata the definition's metadata; empty for one without — then it is not an audit workflow */
    static void apply(List<BlockDefinition> blocks, Map<String, Object> metadata, List<AnalyzerFinding> out) {
        Set<String> names = blockNames(blocks);
        List<String> texts = new ArrayList<>();
        for (BlockDefinition block : blocks) {
            texts.add(BlockText.joined(block));
        }

        unknownBlockReference(blocks, names, texts, out);
        chainOnlyPlaceholder(blocks, texts, out);
        auditOnlyPlaceholder(blocks, texts, metadata, out);
        unwiredOutput(blocks, texts, out);
    }

    /**
     * {@code block.<name>.output} pointing to a block the definition does not know. The resolver
     * leaves such a placeholder unchanged — the prompt receives the string itself
     * instead of the output, mostly unnoticed because the run technically goes through.
     */
    private static void unknownBlockReference(List<BlockDefinition> blocks, Set<String> names,
                                              List<String> texts, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : PlaceholderSyntax.keysIn(texts.get(i))) {
                if (!key.startsWith("block.") || !key.endsWith(".output")) continue;
                String referenced = key.substring("block.".length(), key.length() - ".output".length());
                if (!names.contains(referenced)) {
                    out.add(AnalyzerFinding.of("unknown-block-reference", Severity.HIGH, i,
                            blocks.get(i).getName(),
                            "verweist auf {{ " + key + " }}, aber kein Block heißt \"" + referenced + "\"",
                            "references {{ " + key + " }}, but no block is named \"" + referenced + "\""));
                }
            }
        }
    }

    /** Chain variable in a definition that is not itself a chain stage. */
    private static void chainOnlyPlaceholder(List<BlockDefinition> blocks, List<String> texts,
                                             List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : new LinkedHashSet<>(PlaceholderSyntax.keysIn(texts.get(i)))) {
                if (CHAIN_ONLY.contains(key)) {
                    out.add(AnalyzerFinding.of("chain-only-placeholder", Severity.MEDIUM, i,
                            blocks.get(i).getName(),
                            "{{ " + key + " }} wird nur innerhalb einer Chain belegt; läuft der Workflow "
                                    + "einzeln, steht die Zeichenfolge selbst im Prompt",
                            "{{ " + key + " }} is only set inside a chain; when the workflow runs on its own, "
                                    + "the literal placeholder ends up in the prompt"));
                }
            }
        }
    }

    /**
     * {@code audit_findings} in a definition without valid {@code metadata.audit} (TODO 5ae9bd32). The variable
     * exists only for an audit workflow, decided as at runtime ({@link AuditFindingsContext#appliesTo});
     * otherwise — also as a sub-workflow of an audit workflow — the string itself is in the prompt.
     */
    private static void auditOnlyPlaceholder(List<BlockDefinition> blocks, List<String> texts,
                                             Map<String, Object> metadata, List<AnalyzerFinding> out) {
        if (AuditFindingsContext.appliesTo(metadata)) return;
        for (int i = 0; i < blocks.size(); i++) {
            if (PlaceholderSyntax.keysIn(texts.get(i)).contains(AUDIT_ONLY)) {
                out.add(AnalyzerFinding.of("audit-only-placeholder", Severity.MEDIUM, i, blocks.get(i).getName(),
                        "{{ " + AUDIT_ONLY + " }} wird nur in einem Audit-Workflow belegt (metadata.audit mit "
                                + "enabled, key, category und reportBlock); hier steht die Zeichenfolge selbst im Prompt",
                        "{{ " + AUDIT_ONLY + " }} is only set in an audit workflow (metadata.audit with "
                                + "enabled, key, category and reportBlock); here the literal placeholder ends up in the prompt"));
            }
        }
    }

    /**
     * A block produces output that nobody reads. Every block except the last is checked:
     * does the immediately following one access {@code previous_output}, or does any
     * later block name it via {@code block.<name>.output} or {@code step_N_output}?
     *
     * <p>Only <b>AI blocks</b> are checked. A shell block whose output nobody reads often
     * has a reason for exactly that — it writes a file that the next block reads again,
     * and communicates via the file system instead of placeholders. With an AI block, by contrast, an
     * unread output is paid-for text that nobody gets. A first run over the
     * shipped definitions showed the difference clearly: without this restriction the
     * rule reported over a hundred hits, most of which were legitimate shell chains —
     * and a rule with that much noise gets switched off instead of heeded.
     *
     * <p>Wrapper blocks (foreach, while, …) are likewise excluded: their inner blocks pass
     * outputs on to each other, which looks like a dead output from the outside.
     */
    private static void unwiredOutput(List<BlockDefinition> blocks, List<String> texts,
                                      List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size() - 1; i++) {
            BlockDefinition block = blocks.get(i);
            if (isWrapper(block) || !isAiBlock(block)) continue;

            String next = texts.get(i + 1);
            if (next.contains("previous_output")) continue;

            String byName = block.getName() == null || block.getName().isBlank()
                    ? null : "block." + block.getName() + ".output";
            String byIndex = "step_" + i + "_output"; // step indices are 0-based (BlockStepRunner)
            boolean readLater = false;
            for (int j = i + 1; j < blocks.size() && !readLater; j++) {
                String text = texts.get(j);
                readLater = text.contains(byIndex) || (byName != null && text.contains(byName));
            }
            if (!readLater) {
                out.add(AnalyzerFinding.of("unwired-output", Severity.MEDIUM, i, block.getName(),
                        "die Ausgabe dieses Blocks wird von keinem späteren Block gelesen "
                                + "(weder previous_output noch " + byIndex
                                + (byName == null ? "" : " noch " + byName) + ")",
                        "no later block reads the output of this block "
                                + "(neither previous_output nor " + byIndex
                                + (byName == null ? "" : " nor " + byName) + ")"));
            }
        }
    }

    /** Only here an unread output costs money instead of just a file access. */
    private static boolean isAiBlock(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "simple", "agentic", "skill-capability", "compress-ai" -> true;
            default -> false;
        };
    }

    /** Container blocks run their own wiring — cannot be judged from the outside. */
    private static boolean isWrapper(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "foreach", "while", "conditional", "switch", "retry", "sub-workflow" -> true;
            default -> false;
        };
    }

    private static Set<String> blockNames(List<BlockDefinition> blocks) {
        Set<String> names = new LinkedHashSet<>();
        for (BlockDefinition block : blocks) {
            if (block.getName() != null && !block.getName().isBlank()) names.add(block.getName());
        }
        return names;
    }
}
