package com.automation.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineListenerTest {

    @Test
    void defaultCap_is8KiB_andNoneAcceptsLinesSilently() {
        assertThat(LineListener.DEFAULT_MAX_LINE_BYTES).isEqualTo(8 * 1024);
        assertThat(LineListener.NONE.maxLineBytes()).isEqualTo(8 * 1024);
        LineListener.NONE.onLine("ignored", 5);
    }

    @Test
    void withMaxLineBytes_changesOnlyTheCap_andDelegatesLines() {
        List<String> seen = new ArrayList<>();
        LineListener delegate = (line, dropped) -> seen.add(line + "/" + dropped);

        LineListener capped = LineListener.withMaxLineBytes(16, delegate);
        capped.onLine("a", 0);
        capped.onLine("b", 7);

        assertThat(capped.maxLineBytes()).isEqualTo(16);
        assertThat(delegate.maxLineBytes()).isEqualTo(LineListener.DEFAULT_MAX_LINE_BYTES);
        assertThat(seen).containsExactly("a/0", "b/7");
    }
}
