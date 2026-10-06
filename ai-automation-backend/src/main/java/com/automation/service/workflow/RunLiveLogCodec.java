package com.automation.service.workflow;

import com.automation.service.workflow.RunLiveLogService.LiveLine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Stored form of a live log: gzip-compressed JSON of the step names and lines, with a format version. */
final class RunLiveLogCodec {

    static final int FORMAT_VERSION = 1;

    record Payload(int version, Map<Integer, String> stepNames, List<LiveLine> lines) {
        Payload {
            stepNames = stepNames != null ? Map.copyOf(stepNames) : Map.of();
            lines = lines != null ? List.copyOf(lines) : List.of();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RunLiveLogCodec() {}

    static byte[] encode(Map<Integer, String> stepNames, List<LiveLine> lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(bytes)) {
            MAPPER.writeValue(gzip, new Payload(FORMAT_VERSION, stepNames, lines));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Payload decode(byte[] content) {
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(content))) {
            Payload payload = MAPPER.readValue(gzip, Payload.class);
            if (payload.version() != FORMAT_VERSION) {
                throw new IllegalStateException("Unknown live-log format version " + payload.version());
            }
            return payload;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
