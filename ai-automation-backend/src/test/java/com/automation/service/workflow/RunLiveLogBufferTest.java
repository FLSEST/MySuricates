package com.automation.service.workflow;

import org.junit.jupiter.api.Test;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLiveLogBufferTest {

    @Test
    void ringKeepsNewestLinesAndReportsOverwrittenAndFlushLimitedLinesAsSkipped() {
        RunLiveLogBuffer buffer = new RunLiveLogBuffer(3, 10);
        for (int i = 0; i < 5; i++) {
            buffer.append(i, "stdout", "line-" + i, 20 + i);
        }

        RunLiveLogBuffer.Batch batch = buffer.takeUnsent(2, 1_000);

        assertThat(buffer.firstSeq()).isEqualTo(2);
        assertThat(buffer.lastSeq()).isEqualTo(4);
        assertThat(batch.lines()).extracting(RunLiveLogService.LiveLine::seq).containsExactly(3L, 4L);
        assertThat(batch.lines()).extracting(RunLiveLogService.LiveLine::text).containsExactly("line-3", "line-4");
        assertThat(batch.skipped()).isEqualTo(3);
        assertThat(buffer.takeUnsent(2, 1_000).lines()).isEmpty();
    }

    @Test
    void characterLimitKeepsAtLeastTheNewestLine() {
        RunLiveLogBuffer buffer = new RunLiveLogBuffer(5, 0);
        buffer.append(0, "stdout", "aa", 1);
        buffer.append(0, "stdout", "much-too-long", 2);

        RunLiveLogBuffer.Batch batch = buffer.takeUnsent(5, 3);

        assertThat(batch.lines()).extracting(RunLiveLogService.LiveLine::text)
                .containsExactly("much-too-long");
        assertThat(batch.skipped()).isEqualTo(1);
    }

    @Test
    void stepNamesKeepTheFirstNonOverwritingNameAndExposeAnImmutableSnapshot() {
        RunLiveLogBuffer buffer = new RunLiveLogBuffer(2, 0);
        buffer.nameStep(1, "build");
        buffer.nameStep(1, "renamed");

        var names = buffer.stepNames();

        assertThat(names).containsOnly(entry(1, "build"));
        assertThatThrownBy(() -> names.put(2, "deploy"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void appendReactivatesAFinishedBufferAndUpdatesActivity() {
        RunLiveLogBuffer buffer = new RunLiveLogBuffer(0, 10);
        buffer.markFinished(20);
        buffer.markFinished(30);

        assertThat(buffer.finishedAtMillis()).isEqualTo(20);

        buffer.append(0, "stderr", "failure", 40);

        assertThat(buffer.finishedAtMillis()).isNull();
        assertThat(buffer.lastActivityMillis()).isEqualTo(40);
        assertThat(buffer.since(-1)).hasSize(1);
    }
}
