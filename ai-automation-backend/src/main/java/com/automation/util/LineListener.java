package com.automation.util;

/**
 * Receives the lines of a process output stream while the process is still running
 * (see {@link ProcessOutputTee}). Implementations must be cheap and must not block —
 * they run on the thread that drains the process pipe.
 */
@FunctionalInterface
public interface LineListener {

    LineListener NONE = (line, droppedBytes) -> {};

    int DEFAULT_MAX_LINE_BYTES = 8 * 1024;

    /**
     * @param line         UTF-8 decoded line without its terminator, at most {@link #maxLineBytes()} bytes
     * @param droppedBytes bytes of this line beyond the cap that were not decoded (0 for normal lines)
     */
    void onLine(String line, long droppedBytes);

    /** Upper bound of bytes decoded per line; the rest is only counted. */
    default int maxLineBytes() {
        return DEFAULT_MAX_LINE_BYTES;
    }

    /** Wraps {@code delegate} with a different per-line cap. */
    static LineListener withMaxLineBytes(int maxLineBytes, LineListener delegate) {
        return new LineListener() {
            @Override
            public void onLine(String line, long droppedBytes) {
                delegate.onLine(line, droppedBytes);
            }

            @Override
            public int maxLineBytes() {
                return maxLineBytes;
            }
        };
    }
}
