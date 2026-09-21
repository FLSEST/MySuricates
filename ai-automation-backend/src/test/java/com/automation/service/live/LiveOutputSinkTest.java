package com.automation.service.live;

import com.automation.util.LineListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LiveOutputSinkTest {

    private record Emitted(LiveLineKind kind, String text) {}

    @Test
    void noop_isInactive_andHandsOutTheNoneListener() {
        assertThat(LiveOutputSink.NOOP.isActive()).isFalse();
        assertThat(LiveOutputSink.NOOP.rawLines()).isSameAs(LineListener.NONE);
    }

    @Test
    void realSink_isActive_andForwardsRawLinesAsLine() {
        List<Emitted> emitted = new ArrayList<>();
        LiveOutputSink sink = (kind, text) -> emitted.add(new Emitted(kind, text));

        assertThat(sink.isActive()).isTrue();
        LineListener lines = sink.rawLines();
        lines.onLine("hello", 0);
        lines.onLine("long", 1234);

        assertThat(emitted).containsExactly(
                new Emitted(LiveLineKind.LINE, "hello"),
                new Emitted(LiveLineKind.LINE, "long … (+1234 bytes)"));
        assertThat(lines.maxLineBytes()).isEqualTo(LineListener.DEFAULT_MAX_LINE_BYTES);
    }
}
