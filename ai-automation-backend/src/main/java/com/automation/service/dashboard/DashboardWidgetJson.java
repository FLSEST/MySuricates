package com.automation.service.dashboard;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;

/**
 * The hardened Jackson setup behind every entry point of {@link DashboardWidgetResultParser}: marker
 * text ({@code parse}, via {@link #markerTreeOf}) and field-based candidates ({@code validateOrThrow},
 * {@code fromMetadata}, via {@link #fieldTreeOf}) are bounded to the same nesting depth, but on two
 * separate mappers — neither field is exposed; every caller in the package goes through a method, so
 * nothing else in the package can reconfigure either mapper (fix round 2, H3).
 *
 * <p>Fix round 1: {@code ObjectMapper.valueToTree} does not enforce {@link StreamWriteConstraints} at
 * all — it serializes through {@code TokenBuffer}, which (checked against the project's pinned
 * Jackson 2.18.5) has no nesting guard of its own; a 10 000-deep {@code Map} still overflows the stack
 * with the constraint set on the mapper. {@code ObjectMapper.writeValueAsString} does not have that
 * gap: its generators extend {@code GeneratorBase}, which does check the constraint on every
 * {@code writeStartObject}/{@code writeStartArray}. {@link #fieldTreeOf} therefore always converts a
 * field-based candidate through bounded JSON text and back — never through {@code valueToTree}.
 *
 * <p>Fix round 2 closed two gaps that the write-then-read round trip itself introduced on the field
 * path, both verified against 2.18.5:
 * <ul>
 *   <li><b>H1</b> — by default, a real JSON generator quotes {@code NaN}/{@code Infinity} as the
 *       strings {@code "NaN"}/{@code "Infinity"} ({@code JsonWriteFeature.WRITE_NAN_AS_STRINGS}) and
 *       always base64-encodes a {@code byte[]} into a string — both then read back as an accepted,
 *       ordinary text value, silently reversing the contract's "must be a string" rule for exactly
 *       those two runtime types (ordinary {@code Boolean}/{@code Integer}/{@code Double}/
 *       {@code BigDecimal} values were never affected: they already round-trip to a non-textual node
 *       and were already rejected, with or without this fix). {@code FIELD_MAPPER} disables that
 *       quoting — an unquoted {@code NaN}/{@code Infinity} token is invalid JSON and fails on the read
 *       side back into the same {@code IllegalArgumentException} every other malformed candidate gets
 *       — and replaces the default {@code byte[]} serializer with one that always throws, since no
 *       write-side toggle exists for it the way one does for non-numeric doubles.</li>
 *   <li><b>H2</b> — the read side's string-length budget defaults to the <em>marker scan's</em> budget
 *       ({@link DashboardWidgetMarker#MAX_JSON_CHARS}, 8192), so on the field path it fired before the
 *       contract's own, much smaller per-field caps (500 at most) could — an oversized
 *       {@code summary} (routinely {@code {{ previous_report }}}) got a generic "could not be read"
 *       instead of naming the field. {@code FIELD_MAPPER} uses its own, much larger
 *       {@link #FIELD_MAX_STRING_LENGTH}, decoupled from the marker scan's budget, so the contract's
 *       own limit always speaks first; the marker path keeps the original 8192 unchanged.</li>
 * </ul>
 */
final class DashboardWidgetJson {

    /** Deep enough for any legitimate contract (2 levels); far short of any real recursion risk. */
    static final int MAX_NESTING_DEPTH = 32;

    /**
     * Deliberately far above every contract field cap (500 characters, the largest) so the contract's
     * own limit always speaks first on the field path; still bounded so one pathological string cannot
     * exhaust memory. Independent of the marker scan's budget — that budget belongs to the marker scan,
     * not to the contract (fix round 2, H2).
     */
    private static final int FIELD_MAX_STRING_LENGTH = 1_000_000;

    /** Marker path ({@link DashboardWidgetResultParser#parse}): unchanged since fix round 1. */
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .maxStringLength(DashboardWidgetMarker.MAX_JSON_CHARS)
                            .build())
                    .build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Field path ({@code validateOrThrow}, {@code fromMetadata}): see the class javadoc, H1 + H2. */
    private static final JsonMapper FIELD_MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .maxStringLength(FIELD_MAX_STRING_LENGTH)
                            .build())
                    .streamWriteConstraints(StreamWriteConstraints.builder()
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .build())
                    .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
                    .build())
            .addModule(new SimpleModule().addSerializer(byte[].class, new RejectByteArraySerializer()))
            .build();

    private DashboardWidgetJson() {
    }

    /** The marker path's read call: an already-scanned JSON string, nothing else changed. */
    static JsonNode markerTreeOf(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /**
     * A field-based candidate as a tree, via the bounded write-then-read round trip described in the
     * class javadoc. Throws IllegalArgumentException — never a Jackson type, never an Error — for
     * anything the mapper cannot turn into bounded JSON text; the message names {@code dashboard_widget}
     * and never echoes the candidate.
     */
    static JsonNode fieldTreeOf(Object candidate) {
        try {
            return FIELD_MAPPER.readTree(FIELD_MAPPER.writeValueAsString(candidate));
        } catch (Exception e) {
            throw new IllegalArgumentException("dashboard_widget could not be read");
        }
    }

    /**
     * H1: unlike non-numeric doubles, {@code byte[]} has no write-side "quote me as a rejectable token"
     * toggle — Jackson's default serializer always base64-encodes it into an accepted string. This
     * replaces that default so any {@code byte[]} anywhere in the candidate fails the write side
     * instead.
     */
    private static final class RejectByteArraySerializer extends JsonSerializer<byte[]> {
        @Override
        public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            throw new IOException("byte[] is not a dashboard_widget contract value");
        }
    }
}
