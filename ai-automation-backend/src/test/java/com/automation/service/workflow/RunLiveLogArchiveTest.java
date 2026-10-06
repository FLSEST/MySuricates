package com.automation.service.workflow;

import com.automation.config.LiveLogConfig;
import com.automation.entity.RunLiveLog;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.RunLiveLogRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import com.automation.service.workflow.RunLiveLogService.LiveLine;
import com.automation.service.workflow.RunLiveLogService.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLiveLogArchiveTest {

    private static final String RUN = "run-1";
    private static final String WORKFLOW = "wf-1";

    private final MutableClock clock = new MutableClock();
    private final WorkflowExecutionRegistry registry = mock(WorkflowExecutionRegistry.class);
    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final RunLiveLogRepository repository = mock(RunLiveLogRepository.class);
    /** Rows as the repository would hold them. */
    private final Map<String, RunLiveLog> rows = new HashMap<>();

    private LiveLogConfig config;
    private RunLiveLogService liveLog;
    private RunLiveLogArchive archive;

    @BeforeEach
    void setUp() {
        config = new LiveLogConfig();
        liveLog = new RunLiveLogService(mock(SimpMessagingTemplate.class), config, registry, clock,
                mock(ScheduledExecutorService.class));
        archive = new RunLiveLogArchive(liveLog, repository, workflowRepository, config, clock);
        when(workflowRepository.findLiveLogPersistById(anyString())).thenReturn(Optional.empty());
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        when(repository.save(any(RunLiveLog.class))).thenAnswer(inv -> {
            RunLiveLog row = inv.getArgument(0);
            rows.put(row.getRunId(), row);
            return row;
        });
    }

    private static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        void advanceMinutes(long minutes) { millis.addAndGet(TimeUnit.MINUTES.toMillis(minutes)); }
    }

    private void emit(int stepIndex, String blockName, String... texts) {
        LiveOutputSink sink = liveLog.sinkFor(RUN, stepIndex, blockName);
        for (String text : texts) sink.emit(LiveLineKind.LINE, text);
    }

    private void complete() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, RUN, WORKFLOW, "wf", null, null,
                RunStatus.COMPLETED, null, null, null);
        liveLog.onRunCompleted(event);
        archive.onRunCompleted(event);
    }

    /** Lets the buffer's retention pass so only the stored log is left. */
    private void evict() {
        clock.advanceMinutes(config.getRetentionMinutes() + 1L);
        liveLog.evictExpired();
        assertThat(liveLog.snapshot(RUN, -1).available()).isFalse();
    }

    @Nested
    class Storing {

        @Test
        void keepsTheBufferWhenTheSystemDefaultIsOn() {
            emit(0, "Build", "compiling", "done");

            complete();

            RunLiveLog row = rows.get(RUN);
            assertThat(row).isNotNull();
            assertThat(row.getLineCount()).isEqualTo(2);
            assertThat(row.getBufferId()).isEqualTo(liveLog.snapshot(RUN, -1).bufferId());
            RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(row.getContent());
            assertThat(payload.lines()).extracting(LiveLine::text).containsExactly("compiling", "done");
            assertThat(payload.stepNames()).containsEntry(0, "Build");
        }

        @Test
        void workflowOffWinsOverTheSystemDefault() {
            when(workflowRepository.findLiveLogPersistById(WORKFLOW)).thenReturn(Optional.of("off"));
            emit(0, null, "x");

            complete();

            verify(repository, never()).save(any());
        }

        @Test
        void workflowOnWinsOverASystemDefaultOfOff() {
            config.setPersistDefault(false);
            when(workflowRepository.findLiveLogPersistById(WORKFLOW)).thenReturn(Optional.of("on"));
            emit(0, null, "x");

            complete();

            assertThat(rows).containsKey(RUN);
        }

        @Test
        void systemDefaultOffKeepsNothingForAnUnsetWorkflow() {
            config.setPersistDefault(false);
            emit(0, null, "x");

            complete();

            verify(repository, never()).save(any());
        }

        @Test
        void aRunWithoutLiveLinesKeepsNoRow() {
            complete();

            verify(repository, never()).save(any());
        }

        @Test
        void nothingIsStoredWhenLiveLogsAreOff() {
            emit(0, null, "x");
            config.setEnabled(false);

            complete();

            verify(repository, never()).save(any());
        }

        @Test
        void aFailingSaveNeverEscapesTheListener() {
            emit(0, null, "x");
            when(repository.save(any(RunLiveLog.class))).thenThrow(new IllegalStateException("db down"));

            assertThatCode(RunLiveLogArchiveTest.this::complete).doesNotThrowAnyException();
        }

        @Test
        void sameBufferStoredTwiceIsReplacedNotDuplicated() {
            emit(0, null, "a");
            complete();
            emit(0, null, "b");

            complete();

            RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(rows.get(RUN).getContent());
            assertThat(payload.lines()).extracting(LiveLine::text).containsExactly("a", "b");
        }

        @Test
        void aResumedRunFromANewBufferIsAppendedAndRenumbered() {
            emit(0, "Build", "first-1", "first-2");
            complete();
            evict();
            String firstBuffer = rows.get(RUN).getBufferId();

            emit(1, "Deploy", "second-1");
            complete();

            RunLiveLog row = rows.get(RUN);
            assertThat(row.getBufferId()).isNotEqualTo(firstBuffer);
            RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(row.getContent());
            assertThat(payload.lines()).extracting(LiveLine::text).containsExactly("first-1", "first-2", "second-1");
            assertThat(payload.lines()).extracting(LiveLine::seq).containsExactly(0L, 1L, 2L);
            assertThat(payload.stepNames()).containsEntry(0, "Build").containsEntry(1, "Deploy");
        }

        @Test
        void appendingCapsAtBufferLinesAndCountsTheDroppedAsSkipped() {
            config.setBufferLines(3);
            emit(0, null, "a", "b");
            complete();
            evict();

            emit(0, null, "c", "d");
            complete();

            RunLiveLog row = rows.get(RUN);
            assertThat(row.getSkipped()).isEqualTo(1);
            RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(row.getContent());
            assertThat(payload.lines()).extracting(LiveLine::text).containsExactly("b", "c", "d");
            assertThat(payload.lines()).extracting(LiveLine::seq).containsExactly(1L, 2L, 3L);
        }
    }

    @Nested
    class Replaying {

        @Test
        void servesMemoryWhileTheBufferLives() {
            emit(0, null, "a");
            complete();

            Snapshot snapshot = archive.snapshot(RUN, -1);

            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.stored()).isFalse();
        }

        @Test
        void fallsBackToTheStoredLogWithTheSameSequenceNumbers() {
            emit(2, "Test", "a", "b", "c");
            complete();
            Snapshot fromMemory = archive.snapshot(RUN, -1);
            evict();

            Snapshot stored = archive.snapshot(RUN, -1);

            assertThat(stored.available()).isTrue();
            assertThat(stored.stored()).isTrue();
            assertThat(stored.bufferId()).isEqualTo(fromMemory.bufferId());
            assertThat(stored.lines()).isEqualTo(fromMemory.lines());
            assertThat(stored.firstSeq()).isZero();
            assertThat(stored.lastSeq()).isEqualTo(2);
            assertThat(stored.stepNames()).containsEntry(2, "Test");
        }

        @Test
        void storedReplayHonoursAfterSeqAndReportsRingOverwritesAsSkipped() {
            config.setBufferLines(2);
            emit(0, null, "a", "b", "c");
            complete();
            evict();

            Snapshot all = archive.snapshot(RUN, -1);
            Snapshot tail = archive.snapshot(RUN, 1);

            assertThat(all.firstSeq()).isEqualTo(1);
            assertThat(all.skipped()).isEqualTo(1);
            assertThat(all.lines()).extracting(LiveLine::text).containsExactly("b", "c");
            assertThat(tail.skipped()).isZero();
            assertThat(tail.lines()).extracting(LiveLine::text).containsExactly("c");
        }

        @Test
        void withoutBufferOrRowItStaysUnavailable() {
            Snapshot snapshot = archive.snapshot("run-unknown", -1);

            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.stored()).isFalse();
        }

        @Test
        void anUnreadableRowIsTreatedAsNoLog() {
            rows.put(RUN, RunLiveLog.builder().runId(RUN).bufferId("b").lineCount(1)
                    .content(new byte[]{1, 2, 3}).storedAt(clock.instant()).build());

            assertThat(archive.snapshot(RUN, -1).available()).isFalse();
        }
    }
}
