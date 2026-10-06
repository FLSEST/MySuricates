package com.automation.service.audit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks whether an audit finding relies on a measurement of <b>this</b> run.
 *
 * <p>The audit prompt ties every finding to an evidence line („Jedes Finding nennt in
 * {@code evidence} die FAKT- oder TREFFER-Zeile, auf der es beruht"). Nobody checked that:
 * a finding with an invented evidence or one recycled from the last report became a TODO
 * unchanged — with {@code severity: HIGH} one that sits at the top of the list (TODO a94b56a0).
 *
 * <p>The check is deterministic and works without AI: the names of all {@code FAKT <name>:} and
 * {@code TREFFER <name>:} lines are collected from the run's fact sheet; an
 * {@code evidence} that names none of them counts as unsupported.
 *
 * <p><b>What it does and what it does not.</b> It catches evidence that does not exist in this run —
 * invented names and ones from an earlier report. It <i>cannot</i> judge whether a measurement that
 * actually exists supports the conclusion: anyone who cites {@code FAKT
 * non_main_runtime_timeout_lines} and derives an unsupported claim about a completely different
 * place from it still gets through. That is the limit of a mechanical check; it does move the
 * measurement rule from the prompt into the backend, though, where it cannot be worded away.
 */
final class EvidenceVerification {

    private EvidenceVerification() {}

    /** Header line by which a measurement block's output is recognized as a fact sheet. */
    private static final String FACT_SHEET_MARKER = "## FAKTENBLATT";

    /** {@code FAKT <name>: <value>} and {@code TREFFER <name>: <file>:<line>} — only the name counts. */
    private static final Pattern FACT_NAME = Pattern.compile(
            "^\\s*(?:FAKT|TREFFER)\\s+([A-Za-z0-9_.:-]+)\\s*:", Pattern.MULTILINE);

    /** Anything shorter is not a name that can be searched for in an evidence. */
    private static final int MIN_NAME_LENGTH = 4;

    /**
     * Collects the names of all measurements from the outputs of a run's fact-sheet blocks.
     *
     * @param stepOutputs outputs of all steps, in any order
     * @return names found; <b>empty</b> if the run has no fact sheet — then there is nothing
     *         to check, and {@link #isSupported} lets every finding through
     */
    static Set<String> factNames(List<String> stepOutputs) {
        Set<String> names = new LinkedHashSet<>();
        if (stepOutputs == null) return names;
        for (String output : stepOutputs) {
            if (output == null || !output.contains(FACT_SHEET_MARKER)) continue;
            Matcher matcher = FACT_NAME.matcher(output);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name.length() >= MIN_NAME_LENGTH) names.add(name);
            }
        }
        return names;
    }

    /**
     * @return {@code true} if the evidence names at least one measurement of this run — or
     *         if the run has no fact sheet at all (nothing to check, no suspicion)
     */
    static boolean isSupported(String evidence, Set<String> factNames) {
        if (factNames.isEmpty()) return true;
        if (evidence == null || evidence.isBlank()) return false;
        return factNames.stream().anyMatch(evidence::contains);
    }
}
