package com.automation.service.workflow;

import com.automation.config.LiveLogConfig;
import com.automation.entity.RunLiveLog;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.RunLiveLogRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.RunLiveLogService.LiveLine;
import com.automation.service.workflow.RunLiveLogService.Snapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps a run's live log after the run: when a run completes, the lines still in its in-memory buffer
 * are written to {@code run_live_logs} (one row, gzip JSON) if the workflow's {@code liveLogPersist}
 * or the system default says so. {@link #snapshot} serves the replay endpoint from memory first and
 * falls back to the stored row once the buffer is evicted.
 *
 * <p>Runs that end without a {@link WorkflowRunCompletedEvent} (ghost reaps, startup zombie marking)
 * keep no log. A resumed run whose earlier part was stored from another buffer is appended to it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunLiveLogArchive {

    private final RunLiveLogService liveLog;
    private final RunLiveLogRepository repository;
    private final WorkflowRepository workflowRepository;
    private final LiveLogConfig config;
    private final Clock clock;

    /** Never throws: a later listener of the same event (auto todos, chains) must still run. */
    @EventListener
    public void onRunCompleted(WorkflowRunCompletedEvent event) {
        try {
            store(event.getRunId(), event.getWorkflowId());
        } catch (Exception e) {
            log.warn("Failed to store live log of run {}: {}", event.getRunId(), e.getMessage());
        }
    }

    /** Buffered lines with {@code seq > afterSeq}, from memory or — once evicted — from the stored log. */
    public Snapshot snapshot(String runId, long afterSeq) {
        Snapshot live = liveLog.snapshot(runId, afterSeq);
        if (live.available()) {
            return live;
        }
        return repository.findById(runId)
                .flatMap(row -> decode(row).map(payload -> toSnapshot(row, payload, afterSeq)))
                .orElse(live);
    }

    void store(String runId, String workflowId) {
        if (!config.isEnabled() || runId == null || !shouldPersist(workflowId)) {
            return;
        }
        Snapshot live = liveLog.snapshot(runId, -1);
        if (!live.available() || live.lines().isEmpty()) {
            return;
        }
        Optional<RunLiveLog> previous = repository.findById(runId)
                .filter(row -> !row.getBufferId().equals(live.bufferId()));
        RunLiveLog row = previous.flatMap(prev -> decode(prev).map(payload -> appended(prev, payload, live)))
                .orElseGet(() -> row(runId, live.bufferId(), live.stepNames(), live.lines(), live.firstSeq()));
        repository.save(row);
    }

    private boolean shouldPersist(String workflowId) {
        String stored = workflowId == null ? null : workflowRepository.findLiveLogPersistById(workflowId).orElse(null);
        return LiveLogPersistence.resolve(stored, config.isPersistDefault());
    }

    /**
     * Earlier stored part + the new buffer, renumbered gap-free under a new buffer id and capped to
     * {@code bufferLines} (oldest lines go, counted as skipped like a ring overwrite).
     */
    private RunLiveLog appended(RunLiveLog previous, RunLiveLogCodec.Payload stored, Snapshot live) {
        List<LiveLine> combined = new ArrayList<>(stored.lines());
        combined.addAll(live.lines());
        long skipped = previous.getSkipped() + live.firstSeq();
        int overflow = Math.max(0, combined.size() - Math.max(1, config.getBufferLines()));
        skipped += overflow;
        List<LiveLine> kept = combined.subList(overflow, combined.size());
        List<LiveLine> renumbered = new ArrayList<>(kept.size());
        long seq = skipped;
        for (LiveLine line : kept) {
            renumbered.add(new LiveLine(seq++, line.stepIndex(), line.kind(), line.text(), line.ts()));
        }
        Map<Integer, String> stepNames = new HashMap<>(stored.stepNames());
        stepNames.putAll(live.stepNames());
        return row(previous.getRunId(), UUID.randomUUID().toString(), stepNames, renumbered, skipped);
    }

    private RunLiveLog row(String runId, String bufferId, Map<Integer, String> stepNames,
                           List<LiveLine> lines, long skipped) {
        return RunLiveLog.builder()
                .runId(runId)
                .bufferId(bufferId)
                .lineCount(lines.size())
                .skipped(skipped)
                .content(RunLiveLogCodec.encode(stepNames, lines))
                .storedAt(clock.instant())
                .build();
    }

    private static Optional<RunLiveLogCodec.Payload> decode(RunLiveLog row) {
        try {
            return Optional.of(RunLiveLogCodec.decode(row.getContent()));
        } catch (RuntimeException e) {
            log.warn("Unreadable stored live log of run {}: {}", row.getRunId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Same shape as the in-memory snapshot; {@code skipped} counts lines before the first stored one. */
    private static Snapshot toSnapshot(RunLiveLog row, RunLiveLogCodec.Payload payload, long afterSeq) {
        List<LiveLine> all = payload.lines();
        long firstSeq = all.isEmpty() ? row.getSkipped() : all.get(0).seq();
        long lastSeq = all.isEmpty() ? firstSeq - 1 : all.get(all.size() - 1).seq();
        List<LiveLine> lines = all.stream().filter(line -> line.seq() > afterSeq).toList();
        long skipped = Math.max(0, firstSeq - Math.max(0, afterSeq + 1));
        return new Snapshot(row.getRunId(), row.getBufferId(), true, firstSeq, lastSeq, skipped,
                payload.stepNames(), lines, true);
    }
}
