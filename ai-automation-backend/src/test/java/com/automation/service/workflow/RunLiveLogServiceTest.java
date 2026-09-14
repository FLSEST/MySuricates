package com.automation.service.workflow;

import com.automation.config.LiveLogConfig;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import com.automation.service.workflow.RunLiveLogService.LiveLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunLiveLogServiceTest {

    private static final String RUN = "run-1";
    private static final String TOPIC = "/topic/executions/run-1/logs";

    @Mock private SimpMessagingTemplate template;
    @Mock private WorkflowExecutionRegistry registry;
    @Mock private ScheduledExecutorService scheduler;

    private final MutableClock clock = new MutableClock();
    private LiveLogConfig config;
    private RunLiveLogService service;

    @BeforeEach
    void setUp() {
        config = new LiveLogConfig();
        service = new RunLiveLogService(template, config, registry, clock, scheduler);
    }

    private static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        void advanceMinutes(long minutes) { millis.addAndGet(TimeUnit.MINUTES.toMillis(minutes)); }
    }

    private void emit(int stepIndex, String... texts) {
        LiveOutputSink sink = service.sinkFor(RUN, stepIndex);
        for (String text : texts) sink.emit(LiveLineKind.LINE, text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sentBatches(int expected) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(template, times(expected)).convertAndSend(eq(TOPIC), captor.capture());
        return captor.getAllValues().stream().map(o -> (Map<String, Object>) o).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<LiveLine> lines(Map<String, Object> batch) {
        return (List<LiveLine>) batch.get("lines");
    }

    // ── sink + append ──────────────────────────────────────────────────────

    @Test
    void sinkIsNoop_whenDisabledOrWithoutRunId() {
        assertThat(service.sinkFor(null, 0)).isSameAs(LiveOutputSink.NOOP);
        config.setEnabled(false);
        assertThat(service.sinkFor(RUN, 0)).isSameAs(LiveOutputSink.NOOP);
        assertThat(service.sinkFor(RUN, 0).isActive()).isFalse();
    }

    @Test
    void appendAssignsMonotonicSeq_andSanitizesText() {
        config.setMaxLineLength(10);
        LiveOutputSink sink = service.sinkFor(RUN, 2);
        sink.emit(LiveLineKind.TOOL, "[31mrot[0m");
        sink.emit(LiveLineKind.LINE, "0123456789abcdef");
        sink.emit(LiveLineKind.TEXT, "tab\tbleibt");

        RunLiveLogService.Snapshot snapshot = service.snapshot(RUN, -1);

        assertThat(snapshot.available()).isTrue();
        assertThat(snapshot.lines()).extracting(LiveLine::seq).containsExactly(0L, 1L, 2L);
        assertThat(snapshot.lines()).extracting(LiveLine::text).containsExactly("rot", "0123456789…", "tab\tbleibt");
        assertThat(snapshot.lines()).extracting(LiveLine::kind).containsExactly("tool", "line", "text");
        assertThat(snapshot.lines()).allSatisfy(l -> assertThat(l.stepIndex()).isEqualTo(2));
        assertThat(snapshot.lines().get(0).ts()).isEqualTo(clock.millis());
    }

    @Test
    void aBurstOfLinesSchedulesExactlyOneFlush_andAFlushRearmsIt() {
        emit(0, "a", "b", "c");
        verify(scheduler, times(1)).schedule(any(Runnable.class), eq(250L), eq(TimeUnit.MILLISECONDS));

        service.flush(RUN);
        emit(0, "d");

        verify(scheduler, times(2)).schedule(any(Runnable.class), eq(250L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void scheduledFlushSendsTheBatch() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        emit(1, "hello");
        verify(scheduler).schedule(task.capture(), anyLong(), any());

        task.getValue().run();

        Map<String, Object> batch = sentBatches(1).get(0);
        assertThat(batch).containsEntry("type", "live_output").containsEntry("runId", RUN)
                .containsEntry("skipped", 0L).containsKey("bufferId");
        assertThat(lines(batch)).extracting(LiveLine::text).containsExactly("hello");
    }

    // ── flush ──────────────────────────────────────────────────────────────

    @Test
    void flushSendsOnlyUnsentLines_andNothingWhenNothingIsPending() {
        emit(0, "a", "b");
        service.flush(RUN);
        service.flush(RUN);
        emit(0, "c");
        service.flush(RUN);
        service.flush("unknown-run");

        List<Map<String, Object>> batches = sentBatches(2);
        assertThat(lines(batches.get(0))).extracting(LiveLine::text).containsExactly("a", "b");
        assertThat(lines(batches.get(1))).extracting(LiveLine::text).containsExactly("c");
        assertThat(batches.get(0).get("bufferId")).isEqualTo(batches.get(1).get("bufferId"));
    }

    @Test
    void flushCap_keepsTheNewestLines_andCountsTheRestAsSkipped() {
        config.setMaxLinesPerFlush(3);
        emit(0, "1", "2", "3", "4", "5");
        service.flush(RUN);

        Map<String, Object> batch = sentBatches(1).get(0);
        assertThat(lines(batch)).extracting(LiveLine::text).containsExactly("3", "4", "5");
        assertThat(batch).containsEntry("skipped", 2L);
    }

    @Test
    void flushCharCap_keepsAtLeastTheNewestLine() {
        config.setMaxCharsPerFlush(5);
        emit(0, "aaaa", "bbbb", "cccccccc");
        service.flush(RUN);

        Map<String, Object> batch = sentBatches(1).get(0);
        assertThat(lines(batch)).extracting(LiveLine::text).containsExactly("cccccccc");
        assertThat(batch).containsEntry("skipped", 2L);
    }

    @Test
    void ringOverwrite_showsUpAsSkipped_inFlushAndSnapshot() {
        config.setBufferLines(3);
        emit(0, "1", "2", "3", "4", "5");

        RunLiveLogService.Snapshot snapshot = service.snapshot(RUN, -1);
        assertThat(snapshot.firstSeq()).isEqualTo(2);
        assertThat(snapshot.lastSeq()).isEqualTo(4);
        assertThat(snapshot.skipped()).isEqualTo(2);
        assertThat(snapshot.lines()).extracting(LiveLine::text).containsExactly("3", "4", "5");

        service.flush(RUN);
        Map<String, Object> batch = sentBatches(1).get(0);
        assertThat(batch).containsEntry("skipped", 2L);
        assertThat(lines(batch)).extracting(LiveLine::seq).containsExactly(2L, 3L, 4L);
    }

    @Test
    void failingTemplateIsSwallowed_andTheLinesCountAsSent() {
        doThrow(new IllegalStateException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        emit(0, "a");

        service.flush(RUN);
        service.flush(RUN);

        verify(template, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    // ── snapshot ───────────────────────────────────────────────────────────

    @Test
    void snapshotFiltersByAfterSeq_andReportsUnavailableRuns() {
        emit(0, "a", "b", "c");

        assertThat(service.snapshot(RUN, 1).lines()).extracting(LiveLine::text).containsExactly("c");
        assertThat(service.snapshot(RUN, 1).skipped()).isZero();
        assertThat(service.snapshot(RUN, 2).lines()).isEmpty();

        RunLiveLogService.Snapshot none = service.snapshot("other", -1);
        assertThat(none.available()).isFalse();
        assertThat(none.bufferId()).isNull();
        assertThat(none.lines()).isEmpty();
    }

    // ── completion + eviction ──────────────────────────────────────────────

    private static WorkflowRunCompletedEvent completed(String runId) {
        return new WorkflowRunCompletedEvent(new Object(), runId, "wf", "Workflow", null, null,
                RunStatus.COMPLETED, null, null, null);
    }

    @Test
    void runCompletion_flushesPendingLines_thenRetentionEvictsTheBuffer() {
        emit(0, "last words");
        service.onRunCompleted(completed(RUN));
        assertThat(lines(sentBatches(1).get(0))).extracting(LiveLine::text).containsExactly("last words");

        clock.advanceMinutes(9);
        service.evictExpired();
        assertThat(service.snapshot(RUN, -1).available()).isTrue();

        clock.advanceMinutes(1);
        service.evictExpired();
        assertThat(service.snapshot(RUN, -1).available()).isFalse();
    }

    @Test
    void unregisteredRunWithoutCompletionEvent_isMarkedFirst_thenEvicted() {
        emit(0, "a");
        when(registry.isRunActive(RUN)).thenReturn(false);

        service.evictExpired();
        clock.advanceMinutes(10);
        service.evictExpired();

        assertThat(service.snapshot(RUN, -1).available()).isFalse();
    }

    @Test
    void activeRunIsNeverEvicted_evenAfterACompletionMark() {
        emit(0, "a");
        service.onRunCompleted(completed(RUN));
        when(registry.isRunActive(RUN)).thenReturn(true); // resumed

        clock.advanceMinutes(60);
        service.evictExpired();

        assertThat(service.snapshot(RUN, -1).available()).isTrue();
    }

    @Test
    void appendAfterCompletion_restartsTheRun_soRetentionStartsOver() {
        emit(0, "a");
        service.onRunCompleted(completed(RUN));
        clock.advanceMinutes(9);
        emit(1, "resumed");                 // clears the finished mark
        when(registry.isRunActive(RUN)).thenReturn(false);

        clock.advanceMinutes(5);
        service.evictExpired();             // marks finished now
        clock.advanceMinutes(5);
        service.evictExpired();

        assertThat(service.snapshot(RUN, -1).available()).isTrue();
    }

    @Test
    void maxBufferedRuns_evictsFinishedBuffersBeforeRunningOnes() {
        config.setMaxBufferedRuns(2);
        service.sinkFor("running", 0).emit(LiveLineKind.LINE, "x");
        service.sinkFor("finished", 0).emit(LiveLineKind.LINE, "y");
        service.onRunCompleted(completed("finished"));

        service.sinkFor("new", 0).emit(LiveLineKind.LINE, "z");

        assertThat(service.bufferedRunCount()).isEqualTo(2);
        assertThat(service.snapshot("finished", -1).available()).isFalse();
        assertThat(service.snapshot("running", -1).available()).isTrue();
        assertThat(service.snapshot("new", -1).available()).isTrue();
    }

    @Test
    void recreatedBufferGetsANewBufferId() {
        emit(0, "a");
        String firstId = service.snapshot(RUN, -1).bufferId();
        service.onRunCompleted(completed(RUN));
        clock.advanceMinutes(11);
        service.evictExpired();

        emit(0, "b");

        RunLiveLogService.Snapshot snapshot = service.snapshot(RUN, -1);
        assertThat(snapshot.bufferId()).isNotEqualTo(firstId);
        assertThat(snapshot.lines()).extracting(LiveLine::seq).containsExactly(0L);
    }

    // ── concurrency ────────────────────────────────────────────────────────

    @Test
    void concurrentAppends_produceUniqueContiguousSeqs() throws InterruptedException {
        config.setBufferLines(10_000);
        LiveOutputSink sink = service.sinkFor(RUN, 0);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = IntStream.range(0, 4).mapToObj(t -> Thread.ofPlatform().start(() -> {
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < 500; i++) sink.emit(LiveLineKind.LINE, t + "-" + i);
        })).toList();
        start.countDown();
        for (Thread thread : threads) thread.join();

        assertThat(service.snapshot(RUN, -1).lines()).extracting(LiveLine::seq)
                .containsExactlyElementsOf(IntStream.range(0, 2000).mapToObj(i -> (long) i).toList());
    }

    @Test
    void concurrentFlushes_sendBatchesInAscendingSeqOrder() throws InterruptedException {
        config.setBufferLines(100_000);
        config.setMaxLinesPerFlush(100_000);
        config.setMaxCharsPerFlush(Integer.MAX_VALUE);
        List<Long> firstSeqPerBatch = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<LiveLine> sent = (List<LiveLine>) ((Map<String, Object>) inv.getArgument(1)).get("lines");
            if (!sent.isEmpty()) firstSeqPerBatch.add(sent.get(0).seq());
            return null;
        }).when(template).convertAndSend(eq(TOPIC), any(Object.class));

        LiveOutputSink sink = service.sinkFor(RUN, 0);
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 5000; i++) sink.emit(LiveLineKind.LINE, "l" + i);
        });
        List<Thread> flushers = IntStream.range(0, 3).mapToObj(t -> Thread.ofPlatform().start(() -> {
            while (producer.isAlive()) service.flush(RUN);
        })).toList();
        producer.join();
        for (Thread flusher : flushers) flusher.join();
        service.flush(RUN);

        assertThat(firstSeqPerBatch).isSorted().doesNotHaveDuplicates();
        verify(template, never()).convertAndSend(eq("/topic/executions/other/logs"), any(Object.class));
    }
}
