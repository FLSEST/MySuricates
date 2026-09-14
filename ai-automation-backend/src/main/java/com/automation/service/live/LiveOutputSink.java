package com.automation.service.live;

import com.automation.util.LineListener;

/** Destination for live output lines of the block that is currently executing. */
@FunctionalInterface
public interface LiveOutputSink {

    LiveOutputSink NOOP = (kind, text) -> {};

    void emit(LiveLineKind kind, String text);

    default boolean isActive() {
        return this != NOOP;
    }

    /** Line listener that forwards raw process output as {@link LiveLineKind#LINE}. */
    default LineListener rawLines() {
        if (!isActive()) {
            return LineListener.NONE;
        }
        return (line, droppedBytes) -> emit(LiveLineKind.LINE,
                droppedBytes > 0 ? line + " … (+" + droppedBytes + " bytes)" : line);
    }
}
