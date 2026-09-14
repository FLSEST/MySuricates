package com.automation.service.workflow;

import com.automation.config.LiveLogConfig;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Live output of running blocks: collects the lines a {@link LiveOutputSink} emits per run, sends
 * them in batches of type {@code live_output} to {@code /topic/executions/{runId}/logs} and keeps
 * the newest lines in memory so a client that reloads or opens the run late can replay them
 * ({@code GET /api/v1/executions/{id}/live-log}). Nothing touches the database.
 *
 * <p>Batching: the first unsent line schedules one flush after {@code flushIntervalMs};
 * {@code BlockStepRunner} flushes synchronously at the end of every step so live lines always
 * precede the final step message. Buffers of finished runs are evicted after {@code retentionMinutes}.
 */
@Component
@Slf4j
public class RunLiveLogService {

    public record LiveLine(long seq, int stepIndex, String kind, String text, long ts) {}

    public record Snapshot(String runId, String bufferId, boolean available, long firstSeq, long lastSeq,
                           long skipped, List<LiveLine> lines) {}

    static final String MESSAGE_TYPE = "live_output";
    private static final long EVICTION_PERIOD_SECONDS = 60;
    private static final Pattern ANSI = Pattern.compile("\\[[0-?]*[ -/]*[@-~]|\\][^]*(|\\\\)?|.");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\t]]");

    private final SimpMessagingTemplate messagingTemplate;
    private final LiveLogConfig config;
    private final WorkflowExecutionRegistry registry;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<String, RunLiveLogBuffer> buffers = new ConcurrentHashMap<>();

    @Autowired
    public RunLiveLogService(SimpMessagingTemplate messagingTemplate, LiveLogConfig config,
                             WorkflowExecutionRegistry registry, Clock clock) {
        this(messagingTemplate, config, registry, clock, Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("live-log").factory()));
        scheduler.scheduleWithFixedDelay(this::evictExpiredSafely,
                EVICTION_PERIOD_SECONDS, EVICTION_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** Test constructor: the caller owns the scheduler (and decides whether tasks ever run). */
    RunLiveLogService(SimpMessagingTemplate messagingTemplate, LiveLogConfig config,
                      WorkflowExecutionRegistry registry, Clock clock, ScheduledExecutorService scheduler) {
        this.messagingTemplate = messagingTemplate;
        this.config = config;
        this.registry = registry;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /** Sink for the block running as {@code stepIndex} of {@code runId}; NOOP when live logs are off. */
    public LiveOutputSink sinkFor(String runId, int stepIndex) {
        if (!config.isEnabled() || runId == null) {
            return LiveOutputSink.NOOP;
        }
        return (kind, text) -> append(runId, stepIndex, kind, text);
    }

    void append(String runId, int stepIndex, LiveLineKind kind, String text) {
        RunLiveLogBuffer buffer = buffers.get(runId);
        if (buffer == null) {
            makeRoomForNewBuffer();
            buffer = buffers.computeIfAbsent(runId, id -> new RunLiveLogBuffer(config.getBufferLines(), clock.millis()));
        }
        buffer.append(stepIndex, kind.wire(), sanitize(text), clock.millis());
        if (buffer.flushScheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(() -> flush(runId), config.getFlushIntervalMs(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                buffer.flushScheduled.set(false); // shutting down
            }
        }
    }

    /** Sends all unsent lines of {@code runId} as one batch. Safe to call from any thread. */
    public void flush(String runId) {
        RunLiveLogBuffer buffer = buffers.get(runId);
        if (buffer == null) return;
        buffer.flushLock.lock();
        try {
            buffer.flushScheduled.set(false);
            RunLiveLogBuffer.Batch batch = buffer.takeUnsent(config.getMaxLinesPerFlush(), config.getMaxCharsPerFlush());
            if (batch.lines().isEmpty() && batch.skipped() == 0) return;
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", MESSAGE_TYPE);
            message.put("runId", runId);
            message.put("bufferId", buffer.bufferId);
            message.put("lines", batch.lines());
            message.put("skipped", batch.skipped());
            messagingTemplate.convertAndSend("/topic/executions/" + runId + "/logs", message);
        } catch (Exception e) {
            log.warn("Failed to broadcast live output for run {}: {}", runId, e.getMessage());
        } finally {
            buffer.flushLock.unlock();
        }
    }

    /** Buffered lines with {@code seq > afterSeq}; {@code available=false} when nothing is buffered. */
    public Snapshot snapshot(String runId, long afterSeq) {
        RunLiveLogBuffer buffer = buffers.get(runId);
        if (buffer == null) {
            return new Snapshot(runId, null, false, 0, -1, 0, List.of());
        }
        long firstSeq = buffer.firstSeq();
        List<LiveLine> lines = buffer.since(afterSeq);
        long skipped = Math.max(0, firstSeq - Math.max(0, afterSeq + 1));
        return new Snapshot(runId, buffer.bufferId, true, firstSeq, buffer.lastSeq(), skipped, lines);
    }

    @EventListener
    public void onRunCompleted(WorkflowRunCompletedEvent event) {
        flush(event.getRunId());
        RunLiveLogBuffer buffer = buffers.get(event.getRunId());
        if (buffer != null) buffer.markFinished(clock.millis());
    }

    /**
     * Starts the retention clock for buffers whose run is no longer registered (covers paths that
     * never publish a completion event) and removes buffers whose retention has passed.
     */
    void evictExpired() {
        long now = clock.millis();
        long retentionMillis = TimeUnit.MINUTES.toMillis(config.getRetentionMinutes());
        buffers.forEach((runId, buffer) -> {
            if (registry.isRunActive(runId)) {
                buffer.markActive();
                return;
            }
            Long finishedAt = buffer.finishedAtMillis();
            if (finishedAt == null) {
                buffer.markFinished(now);
            } else if (now - finishedAt >= retentionMillis) {
                buffers.remove(runId, buffer);
            }
        });
    }

    int bufferedRunCount() {
        return buffers.size();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void evictExpiredSafely() {
        try {
            evictExpired();
        } catch (Exception e) {
            log.warn("Live-log eviction failed: {}", e.getMessage());
        }
    }

    private void makeRoomForNewBuffer() {
        while (buffers.size() >= Math.max(1, config.getMaxBufferedRuns())) {
            var victim = buffers.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, RunLiveLogBuffer>>comparingInt(e -> e.getValue().finishedAtMillis() != null ? 0 : 1)
                            .thenComparingLong(e -> e.getValue().finishedAtMillis() != null
                                    ? e.getValue().finishedAtMillis() : e.getValue().lastActivityMillis()));
            if (victim.isEmpty()) return;
            buffers.remove(victim.get().getKey(), victim.get().getValue());
        }
    }

    private String sanitize(String text) {
        if (text == null) return "";
        String clean = CONTROL.matcher(ANSI.matcher(text).replaceAll("")).replaceAll("");
        int max = Math.max(1, config.getMaxLineLength());
        return clean.length() > max ? clean.substring(0, max) + "…" : clean;
    }
}
