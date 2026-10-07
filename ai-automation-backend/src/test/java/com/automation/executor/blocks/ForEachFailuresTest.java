package com.automation.executor.blocks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForEachFailuresTest {

    @Test
    void should_listFailedItems_andErrorOfTheFirst() {
        String summary = ForEachFailures.failureSummary(List.of("a", "b"), "  boom\n", 5);

        assertThat(summary).isEqualTo("2/5 items failed: a, b\n[a]: boom");
    }

    @Test
    void should_truncateLongItemNamesInTheList() {
        String longName = "x".repeat(100);

        String summary = ForEachFailures.failureSummary(List.of(longName), "err", 1);

        assertThat(summary).startsWith("1/1 items failed: " + "x".repeat(60) + "...\n");
        // the error line keeps up to 120 characters of the item label
        assertThat(summary).endsWith("[" + longName + "]: err");
    }

    @Test
    void should_truncateItemLabelOfTheErrorLine_atItsOwnLimit() {
        String veryLong = "y".repeat(ForEachFailures.ITEM_LABEL_MAX_CHARS + 30);

        String summary = ForEachFailures.failureSummary(List.of(veryLong), "err", 1);

        assertThat(summary).endsWith("[" + "y".repeat(ForEachFailures.ITEM_LABEL_MAX_CHARS) + "...]: err");
    }

    @Test
    void should_countUnlistedItems_when_namesExceedTheirBudget() {
        // 60 + "..." = 63 chars per label; after two labels the third one would exceed 160
        List<String> failed = List.of("a".repeat(70), "b".repeat(70), "c".repeat(70), "d".repeat(70));

        String summary = ForEachFailures.failureSummary(failed, "err", 10);

        assertThat(summary).startsWith("4/10 items failed: ");
        assertThat(summary).contains("a".repeat(60) + "..., " + "b".repeat(60) + "...");
        assertThat(summary).doesNotContain("ccc");
        assertThat(summary).contains(", … (+2)");
    }

    @Test
    void should_listAllNames_when_withinTheirBudget() {
        List<String> failed = List.of("first", "second");

        String summary = ForEachFailures.failureSummary(failed, "e", 2);

        assertThat(summary).contains("items failed: first, second\n").doesNotContain("(+");
    }

    @Test
    void should_shortenALongError_toHeadAndTail() {
        String error = "H".repeat(80) + "M".repeat(500) + "T".repeat(240);

        String summary = ForEachFailures.failureSummary(List.of("a"), error, 1);

        assertThat(summary).contains("[a]: " + "H".repeat(80) + "\n[... skipped 500 of 820 characters ...]\n" + "T".repeat(240));
        assertThat(summary).doesNotContain("M");
    }

    @Test
    void should_pluralizeSkippedItemsInTheAbortReason() {
        assertThat(ForEachFailures.abortReason(3, 1))
                .isEqualTo("stopped after 3 consecutive failed items (max_consecutive_failures), 1 item not run");
        assertThat(ForEachFailures.abortReason(3, 0)).endsWith(", 0 items not run");
        assertThat(ForEachFailures.abortReason(5, 7)).isEqualTo(
                "stopped after 5 consecutive failed items (max_consecutive_failures), 7 items not run");
    }

    @Test
    void should_readMaxConsecutiveFailures_fromNumbers() {
        assertThat(ForEachFailures.maxConsecutiveFailures(5)).isEqualTo(5);
        assertThat(ForEachFailures.maxConsecutiveFailures(0)).isZero();
        assertThat(ForEachFailures.maxConsecutiveFailures(-2)).isZero();
        assertThat(ForEachFailures.maxConsecutiveFailures(4.9)).isEqualTo(4);
        assertThat(ForEachFailures.maxConsecutiveFailures(7L)).isEqualTo(7);
    }

    @Test
    void should_readMaxConsecutiveFailures_fromNumericText() {
        assertThat(ForEachFailures.maxConsecutiveFailures(" 6 ")).isEqualTo(6);
        assertThat(ForEachFailures.maxConsecutiveFailures("0")).isZero();
        assertThat(ForEachFailures.maxConsecutiveFailures("-1")).isZero();
    }

    @Test
    void should_fallBackToDefault_when_missingBlankOrUnreadable() {
        int def = ForEachFailures.DEFAULT_MAX_CONSECUTIVE_FAILURES;
        assertThat(def).isEqualTo(3);
        assertThat(ForEachFailures.maxConsecutiveFailures(null)).isEqualTo(def);
        assertThat(ForEachFailures.maxConsecutiveFailures("")).isEqualTo(def);
        assertThat(ForEachFailures.maxConsecutiveFailures("   ")).isEqualTo(def);
        assertThat(ForEachFailures.maxConsecutiveFailures("three")).isEqualTo(def);
        assertThat(ForEachFailures.maxConsecutiveFailures("2.5")).isEqualTo(def);
        assertThat(ForEachFailures.maxConsecutiveFailures(true)).isEqualTo(def);
    }
}
