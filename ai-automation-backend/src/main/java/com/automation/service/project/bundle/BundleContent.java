package com.automation.service.project.bundle;

import com.automation.dto.bundle.ProjectBundle;
import com.automation.dto.bundle.ProjectBundle.BundleChain;
import com.automation.dto.bundle.ProjectBundle.BundleChainEntry;
import com.automation.dto.bundle.ProjectBundle.BundleDefinition;
import com.automation.dto.bundle.ProjectBundle.BundleSchedule;
import com.automation.dto.bundle.ProjectBundle.BundleWorkflow;

import java.util.ArrayList;
import java.util.List;

/**
 * The strings of a bundle that may carry paths and references — a workflow's blocks, instructions,
 * metadata and default context, a chain entry's and a schedule's input context — rewritten into a
 * new bundle. Names, descriptions and keys are never touched. Export (path tokens), import (path
 * mappings) and validation (finding tokens) all walk the same set of places through here.
 */
final class BundleContent {

    /** Field path of the one metadata value that is a path by definition, not text with paths in it. */
    static final String SOURCE_PATH_FIELD = "metadata.workspace.source_path";

    /**
     * @param owner who the value belongs to ({@code workflow name}, {@code chain name}, …) — for hints
     * @param field where it sits below the owner, e.g. {@code blocks[0].config.command}
     */
    @FunctionalInterface
    interface Rewriter {
        String rewrite(String value, String owner, String field);
    }

    private BundleContent() {
    }

    static ProjectBundle rewrite(ProjectBundle bundle, Rewriter rewriter) {
        // null entries pass through: the validator walks the content too and reports them as problems
        List<BundleWorkflow> workflows = bundle.workflows().stream()
                .map(w -> w == null ? null : new BundleWorkflow(w.key(), rewrite(w.definition(), rewriter)))
                .toList();
        List<BundleChain> chains = bundle.chains().stream().map(c -> rewrite(c, rewriter)).toList();
        List<BundleSchedule> schedules = bundle.schedules().stream().map(s -> rewrite(s, rewriter)).toList();
        return new ProjectBundle(bundle.format(), bundle.formatVersion(), bundle.exportedAt(), bundle.appVersion(),
                bundle.project(), workflows, chains, schedules, bundle.paths(), bundle.skillNames(),
                bundle.warnings());
    }

    /** Visits every content string without changing anything. */
    static void scan(ProjectBundle bundle, Rewriter visitor) {
        rewrite(bundle, visitor);
    }

    static BundleDefinition rewrite(BundleDefinition d, Rewriter rewriter) {
        if (d == null) {
            return null;
        }
        String owner = d.name();
        BundleStrings.Rewriter r = (value, field) -> rewriter.rewrite(value, owner, field);
        return new BundleDefinition(d.name(), d.description(), d.icon(),
                d.blocks() == null ? null : BundleStrings.copy(d.blocks(), "blocks", r),
                d.instructions() == null ? null : rewriter.rewrite(d.instructions(), owner, "instructions"),
                d.model(), d.provider(), d.effort(), d.version(),
                d.defaultContext() == null ? null : BundleStrings.copy(d.defaultContext(), "defaultContext", r),
                d.metadata() == null ? null : BundleStrings.copy(d.metadata(), "metadata", r),
                d.activatedSkillNames());
    }

    private static BundleChain rewrite(BundleChain chain, Rewriter rewriter) {
        if (chain == null || chain.entries() == null) {
            return chain;
        }
        List<BundleChainEntry> entries = new ArrayList<>();
        for (int i = 0; i < chain.entries().size(); i++) {
            BundleChainEntry e = chain.entries().get(i);
            String prefix = "entries[" + i + "].inputContext";
            entries.add(e == null ? null : new BundleChainEntry(e.workflowKey(), e.label(), e.sortOrder(),
                    e.stopOnFailure(), e.inputContext() == null ? null : BundleStrings.copy(e.inputContext(), prefix,
                    (value, field) -> rewriter.rewrite(value, chain.name(), field)), e.timeoutMinutes()));
        }
        return new BundleChain(chain.key(), chain.name(), chain.description(), chain.icon(), chain.stopOnFailure(),
                entries);
    }

    private static BundleSchedule rewrite(BundleSchedule s, Rewriter rewriter) {
        if (s == null || s.inputContext() == null) {
            return s;
        }
        String owner = "schedule " + (s.target() == null ? "?" : s.target().label());
        return new BundleSchedule(s.target(), s.cronExpression(), s.scheduleType(), s.scheduledAt(),
                s.delayExpression(), s.description(),
                BundleStrings.copy(s.inputContext(), "inputContext", (value, field) -> rewriter.rewrite(value, owner, field)),
                s.conflictBehavior(), s.catchUpOnResume(), s.maxRuns(), s.endDate());
    }
}
