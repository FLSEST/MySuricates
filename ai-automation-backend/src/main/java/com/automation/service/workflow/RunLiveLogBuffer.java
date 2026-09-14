package com.automation.service.workflow;

import com.automation.service.workflow.RunLiveLogService.LiveLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory ring of the live lines of one run. Sequence numbers are gap-free per buffer; the
 * {@link #bufferId} changes when a buffer is recreated (e.g. a resumed run after eviction), so
 * clients know to reset their de-duplication.
 */
final class RunLiveLogBuffer {

    /** Lines selected for one WebSocket batch plus how many unsent lines were left out. */
    record Batch(List<LiveLine> lines, long skipped) {}

    final String bufferId = UUID.randomUUID().toString();
    /** Set while a flush is scheduled, so a burst of lines schedules one flush. */
    final AtomicBoolean flushScheduled = new AtomicBoolean();
    /** Held while a batch is collected and sent: batches reach the broker in sequence order. */
    final ReentrantLock flushLock = new ReentrantLock();

    private final int capacity;
    private final ArrayDeque<LiveLine> ring;
    /** Block names per step index, so a replaying client can label lines of steps it never saw start. */
    private final Map<Integer, String> stepNames = new HashMap<>();
    private long nextSeq;
    private long lastSentSeq = -1;
    private long lastActivityMillis;
    private Long finishedAtMillis;

    RunLiveLogBuffer(int capacity, long nowMillis) {
        this.capacity = Math.max(1, capacity);
        this.ring = new ArrayDeque<>(Math.min(this.capacity, 1024));
        this.lastActivityMillis = nowMillis;
    }

    synchronized void nameStep(int stepIndex, String blockName) {
        stepNames.putIfAbsent(stepIndex, blockName);
    }

    synchronized Map<Integer, String> stepNames() {
        return Map.copyOf(stepNames);
    }

    synchronized void append(int stepIndex, String kind, String text, long nowMillis) {
        if (ring.size() == capacity) {
            ring.removeFirst();
        }
        ring.addLast(new LiveLine(nextSeq++, stepIndex, kind, text, nowMillis));
        lastActivityMillis = nowMillis;
        finishedAtMillis = null;
    }

    /**
     * Unsent lines, capped to the newest {@code maxLines}/{@code maxChars}. Marks everything up to the
     * newest line as sent; lines the ring already overwrote count as skipped.
     */
    synchronized Batch takeUnsent(int maxLines, int maxChars) {
        long firstUnsent = lastSentSeq + 1;
        long skipped = Math.max(0, firstSeq() - firstUnsent);
        List<LiveLine> pending = since(lastSentSeq);
        if (pending.isEmpty()) {
            lastSentSeq = Math.max(lastSentSeq, nextSeq - 1);
            return new Batch(List.of(), skipped);
        }
        int from = pending.size();
        int chars = 0;
        while (from > 0 && pending.size() - from < maxLines) {
            int length = pending.get(from - 1).text().length();
            if (chars + length > maxChars && from < pending.size()) break;
            chars += length;
            from--;
        }
        lastSentSeq = pending.get(pending.size() - 1).seq();
        return new Batch(List.copyOf(pending.subList(from, pending.size())), skipped + from);
    }

    synchronized List<LiveLine> since(long afterSeq) {
        List<LiveLine> lines = new ArrayList<>();
        for (LiveLine line : ring) {
            if (line.seq() > afterSeq) lines.add(line);
        }
        return lines;
    }

    /** Sequence number of the oldest line still in the ring ({@code lastSeq() + 1} when empty). */
    synchronized long firstSeq() {
        return ring.isEmpty() ? nextSeq : ring.peekFirst().seq();
    }

    synchronized long lastSeq() {
        return nextSeq - 1;
    }

    synchronized long lastActivityMillis() {
        return lastActivityMillis;
    }

    synchronized Long finishedAtMillis() {
        return finishedAtMillis;
    }

    synchronized void markFinished(long nowMillis) {
        if (finishedAtMillis == null) finishedAtMillis = nowMillis;
    }

    synchronized void markActive() {
        finishedAtMillis = null;
    }
}
