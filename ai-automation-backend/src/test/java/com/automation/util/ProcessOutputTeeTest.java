package com.automation.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessOutputTeeTest {

    private record Line(String text, long dropped) {}

    private static final class Recorder implements LineListener {
        final List<Line> lines = new ArrayList<>();
        private final int max;

        Recorder(int max) { this.max = max; }

        Recorder() { this(DEFAULT_MAX_LINE_BYTES); }

        @Override
        public void onLine(String line, long droppedBytes) { lines.add(new Line(line, droppedBytes)); }

        @Override
        public int maxLineBytes() { return max; }

        List<String> texts() { return lines.stream().map(Line::text).toList(); }
    }

    private static List<String> feed(byte[] input, int[] chunks, Recorder recorder, ByteArrayOutputStream raw)
            throws IOException {
        try (OutputStream tee = ProcessOutputTee.wrap(raw, recorder)) {
            int pos = 0;
            for (int chunk : chunks) {
                int n = Math.min(chunk, input.length - pos);
                tee.write(input, pos, n);
                pos += n;
            }
            if (pos < input.length) tee.write(input, pos, input.length - pos);
        }
        return recorder.texts();
    }

    private static List<String> feedAll(String input, Recorder recorder) throws IOException {
        return feed(input.getBytes(StandardCharsets.UTF_8), new int[0], recorder, new ByteArrayOutputStream());
    }

    @Test
    void rawBytesStayIdentical_underRandomChunking() throws IOException {
        byte[] input = ("héllo wörld\r\nzweite 🚀 Zeile\n\nprogress 10%\rprogress 100%\n"
                + "ende ohne newline").getBytes(StandardCharsets.UTF_8);
        byte[] withInvalid = new byte[input.length + 3];
        System.arraycopy(input, 0, withInvalid, 0, input.length);
        withInvalid[input.length] = (byte) 0xC3;           // truncated 2-byte sequence
        withInvalid[input.length + 1] = (byte) 0xFF;       // never valid in UTF-8
        withInvalid[input.length + 2] = '\n';

        Random random = new Random(42);
        List<String> expectedLines = null;
        for (int round = 0; round < 50; round++) {
            int[] chunks = random.ints(40, 1, 7).toArray();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            List<String> lines = feed(withInvalid, chunks, new Recorder(), raw);

            assertThat(raw.toByteArray()).isEqualTo(withInvalid);
            if (expectedLines == null) expectedLines = lines;
            assertThat(lines).isEqualTo(expectedLines);
        }
        assertThat(expectedLines).hasSize(5).startsWith("héllo wörld", "zweite 🚀 Zeile", "", "progress 100%");
        assertThat(expectedLines.get(4)).startsWith("ende ohne newline").hasSize("ende ohne newline".length() + 2);
    }

    @Test
    void splitsOnLfAndCrLf_evenWhenCrAndLfArriveInSeparateWrites() throws IOException {
        Recorder recorder = new Recorder();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream tee = ProcessOutputTee.wrap(raw, recorder)) {
            tee.write("eins\r".getBytes(StandardCharsets.UTF_8));
            tee.write("\nzwei\n".getBytes(StandardCharsets.UTF_8));
            tee.write('d');
            tee.write('\n');
        }

        assertThat(recorder.texts()).containsExactly("eins", "zwei", "d");
        assertThat(raw.toString(StandardCharsets.UTF_8)).isEqualTo("eins\r\nzwei\nd\n");
    }

    @Test
    void loneCarriageReturnOverwritesTheLine() throws IOException {
        assertThat(feedAll("  3%\r 50%\r100%\ndone\n", new Recorder())).containsExactly("100%", "done");
    }

    @Test
    void partialLastLineIsEmittedExactlyOnceOnClose() throws IOException {
        Recorder recorder = new Recorder();
        OutputStream tee = ProcessOutputTee.wrap(new ByteArrayOutputStream(), recorder);
        tee.write("no newline".getBytes(StandardCharsets.UTF_8));
        tee.close();
        tee.close();

        assertThat(recorder.texts()).containsExactly("no newline");
    }

    @Test
    void emptyLinesAreEmitted_butCloseAfterNewlineEmitsNothingMore() throws IOException {
        assertThat(feedAll("a\n\n", new Recorder())).containsExactly("a", "");
    }

    @Test
    void longLinesAreCapped_withDroppedByteCount_rawUnaffected() throws IOException {
        Recorder recorder = new Recorder(10);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        String longLine = "0123456789ABCDEFGHIJ";
        feed((longLine + "\nkurz\n").getBytes(StandardCharsets.UTF_8), new int[]{3, 3, 3}, recorder, raw);

        assertThat(recorder.lines).containsExactly(new Line("0123456789", 10), new Line("kurz", 0));
        assertThat(raw.toString(StandardCharsets.UTF_8)).isEqualTo(longLine + "\nkurz\n");
    }

    @Test
    void capNeverSplitsAMultiByteCharacter() throws IOException {
        Recorder recorder = new Recorder(5);
        // "abcd" (4 bytes) + "ö" (2 bytes) — the cap at 5 would cut "ö" in half
        feedAll("abcdöxyz\n", recorder);

        assertThat(recorder.lines).containsExactly(new Line("abcd", 5));
    }

    @Test
    void throwingListenerIsDisabled_rawOutputStaysComplete() throws IOException {
        List<String> seen = new ArrayList<>();
        LineListener failing = (line, dropped) -> {
            seen.add(line);
            throw new IllegalStateException("boom");
        };
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (OutputStream tee = ProcessOutputTee.wrap(raw, failing)) {
            tee.write("eins\nzwei\ndrei".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(seen).containsExactly("eins");
        assertThat(raw.toString(StandardCharsets.UTF_8)).isEqualTo("eins\nzwei\ndrei");
    }

    @Test
    void wrapWithoutListenerReturnsTheRawStream() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();

        assertThat(ProcessOutputTee.wrap(raw, LineListener.NONE)).isSameAs(raw);
        assertThat(ProcessOutputTee.wrap(raw, null)).isSameAs(raw);
    }

    @Test
    void withMaxLineBytesOverridesTheCap() throws IOException {
        List<String> seen = new ArrayList<>();
        LineListener capped = LineListener.withMaxLineBytes(3, (line, dropped) -> seen.add(line + "+" + dropped));
        try (OutputStream tee = ProcessOutputTee.wrap(new ByteArrayOutputStream(), capped)) {
            tee.write("abcdef\n".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(seen).containsExactly("abc+3");
    }

    @Test
    void utf8BoundaryHandlesCompleteIncompleteAndAsciiEndings() {
        byte[] euro = "a€".getBytes(StandardCharsets.UTF_8); // 'a' + 3 bytes

        assertThat(ProcessOutputTee.utf8Boundary(euro, 4)).isEqualTo(4); // complete
        assertThat(ProcessOutputTee.utf8Boundary(euro, 3)).isEqualTo(1); // 2 of 3 bytes
        assertThat(ProcessOutputTee.utf8Boundary(euro, 2)).isEqualTo(1); // lead byte only
        assertThat(ProcessOutputTee.utf8Boundary(euro, 1)).isEqualTo(1); // ascii
    }
}
