package com.automation.service.workflow;

import com.automation.service.workflow.RunLiveLogService.LiveLine;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLiveLogCodecTest {

    private static byte[] gzip(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    void should_roundTripStepNamesAndLines() {
        List<LiveLine> lines = List.of(
                new LiveLine(1, 0, "stdout", "hello", 1000L),
                new LiveLine(2, 1, "stderr", "Umlaut ä and \"quotes\"", 2000L));

        RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(
                RunLiveLogCodec.encode(Map.of(0, "build", 1, "test"), lines));

        assertThat(payload.version()).isEqualTo(RunLiveLogCodec.FORMAT_VERSION);
        assertThat(payload.stepNames()).containsExactlyInAnyOrderEntriesOf(Map.of(0, "build", 1, "test"));
        assertThat(payload.lines()).containsExactlyElementsOf(lines);
    }

    @Test
    void should_writeGzip() {
        byte[] encoded = RunLiveLogCodec.encode(Map.of(), List.of());

        assertThat(encoded[0] & 0xff).isEqualTo(0x1f);
        assertThat(encoded[1] & 0xff).isEqualTo(0x8b);
    }

    @Test
    void should_treatNullStepNamesAndLines_asEmpty() {
        RunLiveLogCodec.Payload payload = RunLiveLogCodec.decode(RunLiveLogCodec.encode(null, null));

        assertThat(payload.stepNames()).isEmpty();
        assertThat(payload.lines()).isEmpty();
    }

    @Test
    void should_copyCollectionsInThePayload() {
        Map<Integer, String> names = new HashMap<>(Map.of(0, "a"));
        RunLiveLogCodec.Payload payload = new RunLiveLogCodec.Payload(1, names, List.of());
        names.put(1, "b");

        assertThat(payload.stepNames()).containsOnlyKeys(0);
        assertThatThrownBy(() -> payload.lines().add(new LiveLine(1, 0, "x", "y", 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_rejectAnUnknownFormatVersion() throws IOException {
        byte[] future = gzip("{\"version\": 2, \"stepNames\": {}, \"lines\": []}");

        assertThatThrownBy(() -> RunLiveLogCodec.decode(future))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown live-log format version 2");
    }

    @Test
    void should_failWithUncheckedIo_forContentThatIsNotGzip() {
        byte[] plain = "{\"version\": 1}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> RunLiveLogCodec.decode(plain)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void should_failWithUncheckedIo_forGzippedNonJson() throws IOException {
        byte[] garbage = gzip("not json");

        assertThatThrownBy(() -> RunLiveLogCodec.decode(garbage)).isInstanceOf(UncheckedIOException.class);
    }
}
