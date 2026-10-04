package com.automation.executor.blocks;

import com.automation.util.TruncationUtil;

import java.util.List;

import static com.automation.executor.blocks.AgenticPromptBuilder.truncate;

/** How a {@code foreach} reports failed items and when it stops early — split off {@link ForEachBlockExecutor}. */
final class ForEachFailures {

    static final int ITEM_LABEL_MAX_CHARS = 120;
    /** Budget of the failure summary: failed item names, then the error of the first one (head + tail). */
    private static final int FAILED_LABEL_MAX_CHARS = 60;
    private static final int FAILED_NAMES_MAX_CHARS = 160;
    private static final int ERROR_HEAD_CHARS = 80;
    private static final int ERROR_TAIL_CHARS = 240;
    /**
     * Default of {@code max_consecutive_failures} (TODO a6659a46): after this many failed items in a row
     * the loop stops instead of calling the rest — a quota error ("You've hit your session limit") fails
     * every following item too and only burns time. {@code 0} switches it off.
     */
    static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private ForEachFailures() {}

    /**
     * Where the block failed, for the run error (TODO 4a8cabc4): the failed items first, then the
     * first one with its error — {@code "2/5 items failed: a, b\n[a]: <error>"}. The output cannot
     * say that: it lists every item in order, so a failed item sits between successful ones.
     */
    static String failureSummary(List<String> failedItems, String firstFailureOutput, int totalItems) {
        StringBuilder names = new StringBuilder();
        int listed = 0;
        for (String item : failedItems) {
            String label = truncate(item, FAILED_LABEL_MAX_CHARS);
            if (listed > 0 && names.length() + label.length() > FAILED_NAMES_MAX_CHARS) break;
            names.append(listed > 0 ? ", " : "").append(label);
            listed++;
        }
        if (listed < failedItems.size()) {
            names.append(", … (+").append(failedItems.size() - listed).append(")");
        }
        String error = TruncationUtil.smart(firstFailureOutput.strip(), ERROR_HEAD_CHARS, ERROR_TAIL_CHARS);
        return failedItems.size() + "/" + totalItems + " items failed: " + names
                + "\n[" + truncate(failedItems.get(0), ITEM_LABEL_MAX_CHARS) + "]: " + error;
    }

    /** Why the loop stopped early, for the output and the run error. */
    static String abortReason(int consecutiveFailures, int skippedItems) {
        return "stopped after " + consecutiveFailures + " consecutive failed items (max_consecutive_failures), "
                + skippedItems + " " + (skippedItems == 1 ? "item" : "items") + " not run";
    }

    /** {@code max_consecutive_failures}: a number or numeric text; missing or unreadable = default, below 0 = off. */
    static int maxConsecutiveFailures(Object raw) {
        if (raw instanceof Number n) return Math.max(0, n.intValue());
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(s.strip()));
            } catch (NumberFormatException e) {
                return DEFAULT_MAX_CONSECUTIVE_FAILURES;
            }
        }
        return DEFAULT_MAX_CONSECUTIVE_FAILURES;
    }
}
