package com.automation.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MeasuredResponseTest {

    private final MockHttpServletResponse raw = new MockHttpServletResponse();
    private final AtomicInteger hookCalls = new AtomicInteger();
    private final MeasuredResponse response = new MeasuredResponse(raw, hookCalls::incrementAndGet);

    @Test
    void nothingWrittenMeansZeroBytesAndNoHook() {
        response.finish();

        assertThat(response.bytesWritten()).isZero();
        assertThat(hookCalls).hasValue(0);
    }

    @Test
    void countsStreamBytesAndPassesThemThroughUnchanged() throws Exception {
        response.getOutputStream().write('{');
        response.getOutputStream().write("\"a\":1}".getBytes(StandardCharsets.UTF_8), 0, 6);

        assertThat(response.bytesWritten()).isEqualTo(7);
        assertThat(raw.getContentAsString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void hookFiresOnceOnTheFirstWrite() throws Exception {
        response.getOutputStream().write(1);
        response.getOutputStream().write(new byte[10], 0, 10);

        assertThat(hookCalls).hasValue(1);
    }

    /** A committed response can no longer take headers, so the hook must stay quiet. */
    @Test
    void hookDoesNotFireOnACommittedResponse() throws Exception {
        raw.setCommitted(true);

        response.getOutputStream().write(1);

        assertThat(hookCalls).hasValue(0);
        assertThat(response.bytesWritten()).isEqualTo(1);
    }

    @Test
    void writerIsEncodedWithTheResponseCharsetAndCountedAfterFinish() throws Exception {
        raw.setCharacterEncoding("UTF-8");
        PrintWriter writer = response.getWriter();
        writer.write("ä");

        response.finish();

        assertThat(response.getWriter()).isSameAs(writer);
        assertThat(response.bytesWritten()).isEqualTo(2); // one character, two UTF-8 bytes
        assertThat(raw.getContentAsString()).isEqualTo("ä");
        assertThat(hookCalls).hasValue(1);
    }

    @Test
    void writerUsesAnExplicitSingleByteCharset() throws Exception {
        raw.setCharacterEncoding("ISO-8859-1");
        response.getWriter().write("ä");
        response.finish();

        assertThat(response.bytesWritten()).isEqualTo(1);
    }

    @Test
    void unknownCharsetFallsBackToUtf8() throws Exception {
        raw.setCharacterEncoding("no-such-charset");
        response.getWriter().write("ä");
        response.finish();

        assertThat(response.bytesWritten()).isEqualTo(2);
    }

    @Test
    void flushBufferEmptiesOurWriterFirst() throws Exception {
        raw.setCharacterEncoding("UTF-8");
        response.getWriter().write("hello");

        response.flushBuffer();

        assertThat(response.bytesWritten()).isEqualTo(5);
        assertThat(raw.isCommitted()).isTrue();
        assertThat(raw.getContentAsString()).isEqualTo("hello");
    }
}
