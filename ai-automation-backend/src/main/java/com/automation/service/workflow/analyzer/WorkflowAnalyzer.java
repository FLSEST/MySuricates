package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.workflow.PlatformAccessCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a workflow definition deterministically for known construction errors — without an AI call.
 *
 * <p>Trigger: the analysis of PRD-07 (TODO 45509fcb) uncovered seven defects that were <b>all</b>
 * machine-detectable. None was an error of content; they were construction errors that had been costing
 * money and runtime unnoticed for months. That is the project's own lesson — measure before
 * an AI judges — applied to its own workflows.
 *
 * <p><b>Place of effect</b> (decision of 2026-09-19): on demand via the REST API and as a test
 * over the shipped pool definitions. The save path stays untouched — the analyzer
 * reports, it does not block — also fields that sit next to {@code config} ({@code unknown-block-field})
 * are saved by that path instead of being discarded; the detail page shows the finding after every save and
 * pool install ({@code useWorkflowAnalysis}). Since TODO 326d5987 it also runs after every AI generation of a definition
 * ({@link GeneratedWorkflowCheck}): findings go back to the AI or into the result, never into the save path.
 * Only lock (TODO e7e93fba): {@code RunAdmission} refuses unattended starts on
 * {@link #UNATTENDED_REFUSAL_RULES} with {@code HIGH} — saving and manual starts continue to work.
 *
 * <p><b>What it checks today</b> — nineteen rules, each decidable from the text, one of them from the text plus
 * the definition's metadata, one from the text plus the module state, four against the block config schema that
 * the editor also uses — and one that also needs the running instance, which only the live analysis supplies:
 * <ul>
 *   <li>{@code unknown-block-reference} — reference to a block that does not exist</li>
 *   <li>{@code chain-only-placeholder} — chain variable in a workflow running on its own</li>
 *   <li>{@code audit-only-placeholder} — {@code {{ audit_findings }}} in a workflow without valid
 *       {@code metadata.audit}: the variable does not exist there</li>
 *   <li>{@code unwired-output} — generated output that no later block reads</li>
 *   <li>{@code absolute-home-path} — path with a real name under {@code /home/…}</li>
 *   <li>{@code missing-strict-mode} — shell block without {@code set -euo pipefail}</li>
 *   <li>{@code duplicated-instructions} — the block's AI task ({@code command}, {@code goal},
 *       {@code input} … depending on type) or a nested block's (switch case) repeats
 *       {@code workflow.instructions}</li>
 *   <li>{@code dated-model-id} — dated model ID instead of alias</li>
 *   <li>{@code on-no-without-reject-option} — approval with {@code on_no} but without an option that rejects</li>
 *   <li>{@code items-command-without-source} — foreach with {@code items_command} but without
 *       {@code items_source: "command"}: the command never runs</li>
 *   <li>{@code unknown-config-key} — config key that no executor reads (also in nested blocks)</li>
 *   <li>{@code missing-required-config-key} — required key of the block type is missing (for "exactly one of" also: several set)</li>
 *   <li>{@code invalid-config-value} — value outside the fixed values, e.g. {@code on_failure: "fail"}</li>
 *   <li>{@code legacy-config-key} — old key name that still works but is being phased out ({@code workflowId} on
 *       sub-workflow) — LOW</li>
 *   <li>{@code dead-nested-blocks} — {@code config.blocks}: no executor runs a blocks list, the
 *       blocks in it never run — HIGH ({@link BlockShapeRules})</li>
 *   <li>{@code unknown-block-field} — field next to {@code type}/{@code name}/{@code config}/
 *       {@code requiredCapabilities}/{@code skillIds} that no run reads; HIGH if it is a config key
 *       ({@code on_failure} next to instead of inside {@code config})</li>
 *   <li>{@code self-api-call} — block calls the REST API of its own instance (except the key-free
 *       probes); in shell code HIGH, in the AI task MEDIUM for review ({@link SelfAccessRules})</li>
 *   <li>{@code direct-db-access} — block reads the platform database ({@code app_settings},
 *       {@code automation.db} with read access) — HIGH; {@code sqlite3} alone MEDIUM for review</li>
 *   <li>{@code container-without-docker} — block runs in a container, but this instance reaches no Docker
 *       daemon — HIGH; only in the live analysis, which passes {@link InstanceFacts} ({@link InstanceRules})</li>
 *   <li>{@code actor-module-disabled} — approval/human-input casts an AI actor ({@code actor: ai|shadow}),
 *       but the module {@code ai-actors} is off: the step fails at run time — HIGH, a placeholder MEDIUM
 *       ({@link ActorRules}; the only rule that reads the instance's module state)</li>
 * </ul>
 *
 * <p><b>What it deliberately does not check.</b> Three rules of the ticket need more than the text and
 * are therefore not included: redundant AI calls (requires a similarity judgment over two
 * tasks), the frugality ladder ("a shell block would have been enough here" is an assessment,
 * not a measurement) and timeouts that are too tight (needs runtime data from {@code step_results}). A
 * rule that has to guess produces noise — and a checking tool that nobody believes gets
 * switched off.
 *
 * <p><b>A fourth rule failed on this</b> and was removed again (TODO 8e5d13a9):
 * {@code gate-order-without-format} was meant to find prompts that order a {@code gate_status} marker
 * without saying that it has to be at the start of a line. The inventory run yielded 22 hits
 * in 15 shipped definitions — and on review <b>not a single one</b> was real: 18 were
 * shell blocks that write the marker themselves ({@code echo "gate_status: GREEN"}), the other
 * prompts merely <i>mentioned</i> it. One even said explicitly "Gib KEINEN gate_status aus".
 * Distinguishing "orders a marker" from "talks about markers" requires
 * text understanding, not pattern search. Whoever takes it up again needs a different way —
 * such as a field in the definition that makes the gate order explicit.
 */
@Service
public class WorkflowAnalyzer implements PlatformAccessCheck {

    /**
     * Rules whose {@code HIGH} finding refuses an unattended start ({@code RunAdmission},
     * TODO e7e93fba): saving stays allowed, the analyzer only reports there.
     */
    public static final Set<String> UNATTENDED_REFUSAL_RULES = Set.of("self-api-call", "direct-db-access");

    private final ModuleRegistry modules;

    /** Every module on — for tests and tools that check definitions without an instance. */
    public WorkflowAnalyzer() {
        this(ModuleRegistry.allEnabled());
    }

    @Autowired
    public WorkflowAnalyzer(ModuleRegistry modules) {
        this.modules = modules;
    }

    /**
     * The {@code HIGH} findings of the {@link #UNATTENDED_REFUSAL_RULES}, in block order — only the
     * platform access rules, pure pattern search without AI and without a database, cheap enough for every
     * run start.
     */
    @Override
    public List<Violation> unattendedViolations(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<AnalyzerFinding> findings = new ArrayList<>();
        SelfAccessRules.apply(blocks, findings);
        return findings.stream()
                .filter(f -> f.severity() == AnalyzerFinding.Severity.HIGH && UNATTENDED_REFUSAL_RULES.contains(f.rule()))
                .map(f -> new Violation(f.rule(), f.blockIndex(), f.detailEn() != null ? f.detailEn() : f.detail()))
                .toList();
    }

    /** Findings of a definition, most severe first and within equal severity in block order. */
    public List<AnalyzerFinding> analyze(Workflow workflow) {
        return analyze(workflow, null);
    }

    /**
     * Like {@link #analyze(Workflow)}, plus the rules that depend on the running instance
     * ({@code container-without-docker}); {@code instance} null leaves them out.
     */
    public List<AnalyzerFinding> analyze(Workflow workflow, InstanceFacts instance) {
        if (workflow == null || workflow.getBlocks() == null || workflow.getBlocks().isEmpty()) {
            return List.of();
        }
        return analyze(workflow.getBlocks(), workflow.getInstructions(), workflow.getMetadata(), instance);
    }

    /** Like {@link #analyze(Workflow)}, but on a loose block list without metadata — so not an audit workflow. */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions) {
        return analyze(blocks, instructions, Map.of());
    }

    /**
     * Like {@link #analyze(Workflow)}, on a loose block list including the definition's metadata — for
     * pool files and tests. {@code metadata.audit} decides whether {@code {{ audit_findings }}} is set.
     */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions,
                                         Map<String, Object> metadata) {
        return analyze(blocks, instructions, metadata, null);
    }

    /** Like {@link #analyze(List, String, Map)}, plus the instance rules when {@code instance} is set. */
    public List<AnalyzerFinding> analyze(List<BlockDefinition> blocks, String instructions,
                                         Map<String, Object> metadata, InstanceFacts instance) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<AnalyzerFinding> findings = new ArrayList<>();
        WiringRules.apply(blocks, metadata != null ? metadata : Map.of(), findings);
        HygieneRules.apply(blocks, instructions, findings);
        ApprovalRules.apply(blocks, findings);
        ForEachRules.apply(blocks, findings);
        ConfigKeyRules.apply(blocks, findings);
        BlockShapeRules.apply(blocks, findings);
        SelfAccessRules.apply(blocks, findings);
        RunTokenRules.apply(blocks, metadata != null ? metadata : Map.of(), findings);
        InstanceRules.apply(blocks, instance, findings);
        ActorRules.apply(blocks, modules.isEnabled(ModuleCatalog.AI_ACTORS), findings);
        findings.sort((a, b) -> a.severity() != b.severity()
                ? a.severity().compareTo(b.severity())
                : Integer.compare(a.blockIndex(), b.blockIndex()));
        return List.copyOf(findings);
    }
}
