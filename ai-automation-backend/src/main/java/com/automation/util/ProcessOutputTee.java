package com.automation.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Output stream that forwards every byte unchanged to a raw buffer and additionally splits the
 * stream into lines for a {@link LineListener}. The raw buffer always receives the bytes first,
 * so the collected output is byte-identical to reading the stream without the tee.
 *
 * <p>Line rules: {@code \n} ends a line, a {@code \r} directly before it is dropped (also across
 * write calls), a lone {@code \r} resets the current line (progress bars overwrite themselves).
 * {@link #close()} emits a pending partial line once and does not close the raw stream.
 * A listener that throws is disabled; the raw stream keeps receiving everything.
 */
@Slf4j
public final class ProcessOutputTee extends OutputStream {

    private static final int INITIAL_LINE_BUFFER = 256;

    private final OutputStream raw;
    private final int maxLineBytes;
    private LineListener listener;
    private byte[] line = new byte[INITIAL_LINE_BUFFER];
    private int length;
    private long dropped;
    private boolean pendingCr;
    private boolean closed;

    private ProcessOutputTee(OutputStream raw, LineListener listener) {
        this.raw = raw;
        this.listener = listener;
        this.maxLineBytes = Math.max(1, listener.maxLineBytes());
    }

    /** Returns {@code raw} itself when there is nobody listening. */
    public static OutputStream wrap(OutputStream raw, LineListener listener) {
        if (listener == null || listener == LineListener.NONE) {
            return raw;
        }
        return new ProcessOutputTee(raw, listener);
    }

    @Override
    public void write(int b) throws IOException {
        raw.write(b);
        accept((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        raw.write(b, off, len);
        for (int i = off; i < off + len; i++) {
            accept(b[i]);
        }
    }

    @Override
    public void flush() throws IOException {
        raw.flush();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (length > 0 || dropped > 0) {
            emit();
        }
    }

    private void accept(byte b) {
        if (listener == LineListener.NONE) return;
        if (pendingCr) {
            pendingCr = false;
            if (b == '\n') {
                emit();
                return;
            }
            // Lone CR: the terminal would overwrite the line — keep only what follows.
            length = 0;
            dropped = 0;
        }
        if (b == '\n') {
            emit();
        } else if (b == '\r') {
            pendingCr = true;
        } else if (length < maxLineBytes) {
            if (length == line.length) {
                line = Arrays.copyOf(line, Math.min(maxLineBytes, line.length * 2));
            }
            line[length++] = b;
        } else {
            dropped++;
        }
    }

    private void emit() {
        int end = length;
        long droppedBytes = dropped;
        if (droppedBytes > 0) {
            int boundary = utf8Boundary(line, end);
            droppedBytes += end - boundary;
            end = boundary;
        }
        String text = new String(line, 0, end, StandardCharsets.UTF_8);
        length = 0;
        dropped = 0;
        try {
            listener.onLine(text, droppedBytes);
        } catch (RuntimeException e) {
            log.debug("Line listener failed, disabling it: {}", e.getMessage());
            listener = LineListener.NONE;
        }
    }

    /** Largest index {@code <= end} that does not split a multi-byte UTF-8 sequence. */
    static int utf8Boundary(byte[] bytes, int end) {
        int i = end;
        int continuation = 0;
        while (i > 0 && continuation < 3 && (bytes[i - 1] & 0xC0) == 0x80) {
            i--;
            continuation++;
        }
        if (i == 0) return end;
        int lead = bytes[i - 1] & 0xFF;
        int expected = lead >= 0xF0 ? 3 : lead >= 0xE0 ? 2 : lead >= 0xC0 ? 1 : 0;
        if (expected == 0) return end;                  // ASCII or stray continuation bytes: nothing to fix
        return continuation < expected ? i - 1 : end;   // incomplete sequence: cut before its lead byte
    }
}
