package com.automation.executor.blocks;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.StepFailureSummary;
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

    private static StepResult ok() {
        return StepResult.builder().status(StepStatus.COMPLETED).output("fine").build();
    }

    private static StepResult failed(String output) {
        return StepResult.builder().status(StepStatus.FAILED).output(output).build();
    }

    @Test
    void tally_stopsAfterMaxFailuresInARow_onlyWhileItemsAreLeft() {
        ForEachFailures failures = new ForEachFailures(5, 2, false);

        failures.record(0, "a", failed("x"));
        failures.record(1, "b", ok());
        failures.record(2, "c", failed("y"));
        assertThat(failures.aborted()).as("a success in between resets the count").isFalse();
        failures.record(3, "d", failed("z"));

        assertThat(failures.aborted()).isTrue();
        assertThat(failures.abortReason()).isEqualTo(ForEachFailures.abortReason(2, 1));
        assertThat(failures.failsTheBlock()).isTrue();

        ForEachFailures lastItems = new ForEachFailures(2, 2, false);
        lastItems.record(0, "a", failed("x"));
        lastItems.record(1, "b", failed("y"));
        assertThat(lastItems.aborted()).as("nothing left to skip: the loop simply ended").isFalse();
        assertThat(lastItems.abortReason()).isNull();

        ForEachFailures off = new ForEachFailures(4, 0, false);
        for (int i = 0; i < 3; i++) off.record(i, "i" + i, failed("x"));
        assertThat(off.aborted()).as("max_consecutive_failures 0 switches the stop off").isFalse();
    }

    @Test
    void tally_continueOnError_toleratesFailedItems_butNotAnAbort() {
        ForEachFailures tolerated = new ForEachFailures(3, 3, true);
        tolerated.record(0, "a", failed("x"));
        tolerated.record(1, "b", ok());
        assertThat(tolerated.failsTheBlock()).isFalse();

        ForEachFailures aborted = new ForEachFailures(5, 2, true);
        aborted.record(0, "a", failed("x"));
        aborted.record(1, "b", failed("y"));
        assertThat(aborted.failsTheBlock()).isTrue();

        ForEachFailures clean = new ForEachFailures(1, 3, false);
        clean.record(0, "a", ok());
        assertThat(clean.failsTheBlock()).isFalse();
    }

    @Test
    void report_continueOnError_notesTheFailedItems_withoutARunError() {
        ForEachFailures failures = new ForEachFailures(3, 3, true);
        failures.record(0, "a", failed("boom"));
        failures.record(1, "b", ok());
        StepResult aggregate = new StepResult();
        StringBuilder outputs = new StringBuilder("[a]: boom\n[b]: fine\n");

        failures.report(aggregate, outputs);

        assertThat(aggregate.getMetadata()).containsEntry("failedItemCount", 1)
                .containsEntry("failedItems", List.of("a"))
                .doesNotContainKeys("abortedAfterConsecutiveFailures", StepFailureSummary.KEY);
        assertThat(outputs.toString()).endsWith("\n[continue_on_error] 1/3 items failed: a");
    }

    @Test
    void report_abort_putsTheReasonBeforeTheSummary_ofTheFirstFailure() {
        ForEachFailures failures = new ForEachFailures(4, 2, false);
        failures.record(0, "a", failed(null));
        failures.record(1, "b", failed("second"));
        StepResult aggregate = new StepResult();
        StringBuilder outputs = new StringBuilder();

        failures.report(aggregate, outputs);

        String reason = ForEachFailures.abortReason(2, 2);
        assertThat(aggregate.getMetadata()).containsEntry("abortedAfterConsecutiveFailures", 2)
                .containsEntry("skippedItemCount", 2)
                .containsEntry("failedItemCount", 2)
                .containsEntry(StepFailureSummary.KEY,
                        reason + "\n" + ForEachFailures.failureSummary(List.of("a", "b"), "", 4));
        assertThat(outputs.toString()).isEqualTo("\n[max_consecutive_failures] " + reason);
    }

    @Test
    void report_noFailures_writesNothing() {
        ForEachFailures failures = new ForEachFailures(1, 3, false);
        failures.record(0, "a", ok());
        StepResult aggregate = new StepResult();
        StringBuilder outputs = new StringBuilder("[a]: fine");

        failures.report(aggregate, outputs);

        assertThat(aggregate.getMetadata()).isNullOrEmpty();
        assertThat(outputs.toString()).isEqualTo("[a]: fine");
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
