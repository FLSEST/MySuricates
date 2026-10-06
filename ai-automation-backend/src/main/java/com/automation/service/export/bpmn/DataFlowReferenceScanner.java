package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.executor.PlaceholderSyntax;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives deterministically from the block configs which block output flows into which block —
 * without a run, without AI. Basis of the data flow export (data objects/associations).
 *
 * <p>Recognized forms (same placeholder notation as at runtime,
 * {@link PlaceholderSyntax#PLACEHOLDER}):
 * <ul>
 *   <li>{@code {{ block.&lt;name&gt;.output }}} — source is the block with this name</li>
 *   <li>{@code {{ previous_output }}} — source is the immediately preceding block</li>
 *   <li>{@code {{ step_&lt;N&gt;_output }}} — source is the block with index N. <b>0-based</b>, as
 *       {@code StepIndexCounter} assigns the step indices and {@code BlockStepRunner} sets the variable
 *       {@code step_&lt;stepIndex&gt;_output}. N is, however, a <em>runtime</em> step index:
 *       wrapper blocks (foreach, while, retry) draw a further index per item/iteration, after which
 *       the mapping step index → block list index no longer holds. If one of these types is
 *       in {@code blocks[0..N]}, the reference is discarded — better no edge than a wrong one.</li>
 *   <li>{@code source} of the compression blocks ({@code compress}, {@code compress-ai}):
 *       block name or {@code "previous"} (see {@code CompressionSources})</li>
 * </ul>
 *
 * <p>Levels: all string values of a block config are scanned recursively. Position-based
 * placeholders ({@code previous_output}, {@code step_N_output}) apply only to the <b>top
 * level</b> — the immediate values of the config map. Further down (switch cases, nested
 * block lists) they mean the inner level at runtime, not the top-level block list; there
 * only {@code block.&lt;name&gt;.output} is therefore evaluated, whose namespace is flat.
 *
 * <p>Branches (7557b928): a reference found this way counts for the <em>branch block</em> it
 * is in, not for the switch — that becomes a gateway, and BPMN allows no data
 * associations on gateways. The endpoint is therefore the task of the branch block ({@link Endpoint#branchPath()},
 * notation in {@link BpmnBranchBlocks}). Conversely a named case block is also a
 * <em>source</em>: its output is addressable at runtime as {@code block.&lt;name&gt;.output}
 * (12a3b244).
 *
 * <p>Further limits (deliberate): sources must be in the workflow <em>before</em> the consuming
 * top-level block — within the same switch no edge arises, because at runtime
 * only one branch runs. Ambiguous (multiply assigned) block names are skipped, because at
 * runtime it cannot be determined which block is meant.
 */
final class DataFlowReferenceScanner {

    /**
     * Endpoint of a reference: the top-level block {@code blockIndex} or, for a block in
     * one of its branches, this branch block. {@code branchPath} is empty for the block itself and
     * otherwise the id path relative to the top-level block (e.g. {@code _c0_1}, {@code _default_0}).
     */
    record Endpoint(int blockIndex, String branchPath) {

        static Endpoint topLevel(int blockIndex) {
            return new Endpoint(blockIndex, "");
        }

        /** Id base of the associated node; {@code rootPrefix} is the prefix of the top-level chain. */
        String idBase(String rootPrefix) {
            return BpmnBranchBlocks.member(rootPrefix, blockIndex) + branchPath;
        }
    }

    /** The output of {@code source} flows into {@code target}. */
    record Reference(Endpoint source, Endpoint target) {

        Reference(int sourceIndex, int targetIndex) {
            this(Endpoint.topLevel(sourceIndex), Endpoint.topLevel(targetIndex));
        }

        int sourceIndex() {
            return source.blockIndex();
        }

        int targetIndex() {
            return target.blockIndex();
        }
    }

    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d{1,6})_output");
    private static final String BLOCK_PREFIX = "block.";
    private static final String OUTPUT_SUFFIX = ".output";
    /** Block types that consume more than one step index at runtime. */
    private static final Set<String> WRAPPER_TYPES = Set.of("foreach", "while", "retry");

    private DataFlowReferenceScanner() {
    }

    static List<Reference> scan(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.size() < 2) {
            return List.of();
        }
        Map<String, Endpoint> byName = uniqueNameIndex(blocks);
        Set<Reference> found = new LinkedHashSet<>();
        for (int target = 0; target < blocks.size(); target++) {
            BlockDefinition block = blocks.get(target);
            Endpoint self = Endpoint.topLevel(target);
            DataFlowConfigStrings texts = DataFlowConfigStrings.of(block.getConfig());
            for (String text : texts.direct()) {
                // A branch command of a conditional ends at ITS task, not at the gateway --
                // BPMN allows no data association there, the edge was dropped (TODO 7557b928).
                Endpoint endpoint = conditionalBranchEndpoint(block, text, target).orElse(self);
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, sourceOf(key, target, byName, blocks), endpoint);
                }
            }
            List<BpmnBranchBlocks.Branch> branches = BpmnBranchBlocks.of(block);
            if (branches.isEmpty()) {
                // Nested values without blocks of their own (e.g. integration params) count for the block.
                for (String text : texts.nested()) {
                    for (String key : PlaceholderSyntax.keysIn(text)) {
                        add(found, namedSource(key, byName), self);
                    }
                }
            } else {
                scanBranches(found, branches, target, byName);
            }
            add(found, compressionSource(block, target, byName), self);
        }
        List<Reference> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparingInt(Reference::targetIndex)
                .thenComparing(r -> r.target().branchPath())
                .thenComparingInt(Reference::sourceIndex)
                .thenComparing(r -> r.source().branchPath()));
        return List.copyOf(sorted);
    }

    /**
     * Does this config text end at the branch task of a {@code conditional}?
     *
     * <p>{@code conditional} carries its branches as commands, not as blocks
     * ({@code then_command}/{@code else_command}), and the export turns them into the tasks
     * {@code _then} and {@code _else}. A reference in there belongs to this task; without the
     * distinction it would land at the conditional block itself, i.e. at the gateway, and be
     * discarded (TODO 7557b928). Other direct values of the block — above all {@code condition} —
     * stay with the block and thus remain without association: that is the notation limit, not a
     * gap.
     *
     * <p>The level semantics stay those of the block: a branch command is a direct
     * config value, so {@code previous_output} and {@code step_N_output} count there too —
     * unlike in the nested block lists of a switch.
     */
    private static Optional<Endpoint> conditionalBranchEndpoint(BlockDefinition block, String text,
                                                                int target) {
        if (!"conditional".equals(block.getType()) || block.getConfig() == null) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> branch : CONDITIONAL_BRANCHES.entrySet()) {
            Object command = block.getConfig().get(branch.getKey());
            if (command instanceof String s && !s.isBlank() && s.equals(text)) {
                return Optional.of(new Endpoint(target, branch.getValue()));
            }
        }
        return Optional.empty();
    }

    /** Config key of the branch command -> id suffix of its task (see BpmnGatewayBuilder). */
    private static final Map<String, String> CONDITIONAL_BRANCHES =
            Map.of("then_command", "_then", "else_command", "_else");

    /**
     * Branch blocks of a switch: every reference ends at the task of the branch block. Within a
     * branch the level semantics apply throughout — only {@code block.<name>.output} and the
     * block name as compression source, never position-based placeholders.
     */
    private static void scanBranches(Set<Reference> found, List<BpmnBranchBlocks.Branch> branches,
                                     int target, Map<String, Endpoint> byName) {
        for (BpmnBranchBlocks.Branch branch : branches) {
            Endpoint self = new Endpoint(target, branch.path());
            DataFlowConfigStrings texts = DataFlowConfigStrings.of(branch.block().getConfig());
            List<String> own = new ArrayList<>(texts.direct());
            // If the branch block is itself a branching, its deeper values are already in this list
            // as branch blocks of their own — otherwise the same reference would count twice.
            if (BpmnBranchBlocks.of(branch.block()).isEmpty()) {
                own.addAll(texts.nested());
            }
            for (String text : own) {
                for (String key : PlaceholderSyntax.keysIn(text)) {
                    add(found, namedSource(key, byName), self);
                }
            }
            add(found, namedCompressionSource(branch.block(), byName), self);
        }
    }

    private static void add(Set<Reference> found, Endpoint source, Endpoint target) {
        // Backward references only: a source after the target (or in the same top-level block)
        // yields nothing at runtime, the placeholder would stay literal.
        if (source != null && source.blockIndex() >= 0 && source.blockIndex() < target.blockIndex()) {
            found.add(new Reference(source, target));
        }
    }

    /**
     * Source of a placeholder on the top level (direct config value of the block).
     * {@code null} if the key does not designate a block output or the source is not unambiguous.
     */
    private static Endpoint sourceOf(String key, int target, Map<String, Endpoint> byName, List<BlockDefinition> blocks) {
        if ("previous_output".equals(key)) {
            return Endpoint.topLevel(target - 1);
        }
        Endpoint named = namedSource(key, byName);
        if (named != null) {
            return named;
        }
        Matcher step = STEP_OUTPUT.matcher(key);
        if (!step.matches()) {
            return null;
        }
        int index = Integer.parseInt(step.group(1));
        // step_N is a RUNTIME step index, not a block list index: wrapper blocks draw a further index per item
        // or iteration (StepIndexCounter), after which N points to a different
        // step than block N. Not statically resolvable ⇒ better no edge than a wrong one.
        return hasWrapperUpTo(blocks, index) ? null : Endpoint.topLevel(index);
    }

    /** Only {@code block.<name>.output} — the only placeholder with a level-independent namespace. */
    private static Endpoint namedSource(String key, Map<String, Endpoint> byName) {
        if (key.startsWith(BLOCK_PREFIX) && key.endsWith(OUTPUT_SUFFIX)
                && key.length() > BLOCK_PREFIX.length() + OUTPUT_SUFFIX.length()) {
            return byName.get(key.substring(BLOCK_PREFIX.length(), key.length() - OUTPUT_SUFFIX.length()));
        }
        return null;
    }

    private static boolean hasWrapperUpTo(List<BlockDefinition> blocks, int index) {
        for (int i = 0; i <= index && i < blocks.size(); i++) {
            if (WRAPPER_TYPES.contains(blocks.get(i).getType())) {
                return true;
            }
        }
        return false;
    }

    /** {@code source} of compress/compress-ai: block name or {@code "previous"}. */
    private static Endpoint compressionSource(BlockDefinition block, int target, Map<String, Endpoint> byName) {
        String value = compressionSourceValue(block);
        if (value == null) {
            return null;
        }
        return "previous".equals(value) ? Endpoint.topLevel(target - 1) : byName.get(value);
    }

    /** Like {@link #compressionSource}, but without {@code "previous"} — in a branch that means the inner level. */
    private static Endpoint namedCompressionSource(BlockDefinition block, Map<String, Endpoint> byName) {
        String value = compressionSourceValue(block);
        return value == null || "previous".equals(value) ? null : byName.get(value);
    }

    private static String compressionSourceValue(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        if (!"compress".equals(type) && !"compress-ai".equals(type)) {
            return null;
        }
        Object raw = block.getConfig() == null ? null : block.getConfig().get("source");
        return raw instanceof String source && !source.isBlank() ? source.strip() : null;
    }

    /**
     * Block name → endpoint, over top-level blocks <em>and</em> their branch blocks: the namespace
     * of {@code block.<name>.output} is flat at runtime (12a3b244). Multiply assigned names
     * drop out (ambiguous at runtime).
     */
    private static Map<String, Endpoint> uniqueNameIndex(List<BlockDefinition> blocks) {
        Map<String, Endpoint> byName = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            put(byName, ambiguous, blocks.get(i).getName(), Endpoint.topLevel(i));
            for (BpmnBranchBlocks.Branch branch : BpmnBranchBlocks.of(blocks.get(i))) {
                put(byName, ambiguous, branch.block().getName(), new Endpoint(i, branch.path()));
            }
        }
        ambiguous.forEach(byName::remove);
        return byName;
    }

    private static void put(Map<String, Endpoint> byName, Set<String> ambiguous, String name, Endpoint endpoint) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (byName.putIfAbsent(name.strip(), endpoint) != null) {
            ambiguous.add(name.strip());
        }
    }
}
