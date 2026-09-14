package com.automation.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stdin bridge between WebSocket input and docker-java's stdin pump thread.
 *
 * <p>Replaces {@code PipedInputStream}/{@code PipedOutputStream}, which tie the pipe to the liveness of
 * the last writer thread: with virtual threads every WebSocket message is handled on a thread that ends
 * right after the write, so the pump saw "Pipe broken" after ~2 s idle and every later keystroke failed
 * with "Read end dead" (TODO 774fa9dc). This stream only knows chunks and an explicit close.
 *
 * <p>Any number of threads may call {@link #write}; exactly one thread (the pump) reads.
 */
final class TerminalStdinStream extends InputStream {

    /** Pending input a stalled container may accumulate before further writes are rejected. */
    static final long MAX_PENDING_BYTES = 1024 * 1024;

    private static final byte[] EOF = new byte[0];

    private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
    private final AtomicLong pendingBytes = new AtomicLong();
    private volatile boolean closed;

    // Reader-side state, touched only by the single pump thread.
    private byte[] current;
    private int position;

    void write(byte[] data) throws IOException {
        if (closed) {
            throw new IOException("Terminal stdin closed");
        }
        if (data.length == 0) {
            return;
        }
        if (pendingBytes.addAndGet(data.length) > MAX_PENDING_BYTES) {
            pendingBytes.addAndGet(-data.length);
            throw new IOException("Terminal stdin buffer full (" + MAX_PENDING_BYTES + " bytes pending)");
        }
        chunks.add(data.clone());
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        return read(single, 0, 1) == -1 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        if (current == null || position == current.length) {
            try {
                current = chunks.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for terminal input");
            }
            position = 0;
            if (current == EOF) {
                chunks.add(EOF); // keep end-of-stream sticky for repeated reads
                return -1;
            }
            pendingBytes.addAndGet(-current.length);
        }
        int count = Math.min(length, current.length - position);
        System.arraycopy(current, position, buffer, offset, count);
        position += count;
        return count;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        chunks.add(EOF);
    }
}
