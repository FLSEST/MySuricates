package com.automation.service.dashboard;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The one hardened Jackson mapper behind every entry point of {@link DashboardWidgetResultParser}:
 * marker text ({@code parse}) and field-based candidates ({@code validateOrThrow}, {@code
 * fromMetadata}) alike are bounded to the same nesting depth on both the read and the write side, so
 * neither path can turn a pathological input into an uncaught {@link StackOverflowError}.
 *
 * <p>Fix round 1: {@code ObjectMapper.valueToTree} does not enforce {@link StreamWriteConstraints} at
 * all — it serializes through {@code TokenBuffer}, which (checked against Jackson 2.19.4) has no
 * nesting guard of its own; a 10 000-deep {@code Map} still overflows the stack with the constraint
 * set on the mapper. {@code ObjectMapper.writeValueAsString} does not have that gap: its generators
 * extend {@code GeneratorBase}, which does check the constraint on every {@code writeStartObject}/
 * {@code writeStartArray}. {@link #treeOf} therefore always converts a field-based candidate through
 * bounded JSON text and back — the same bounded {@code readTree} the marker path already trusts —
 * never through {@code valueToTree}.
 */
final class DashboardWidgetJson {

    /** Deep enough for any legitimate contract (2 levels); far short of any real recursion risk. */
    static final int MAX_NESTING_DEPTH = 32;

    static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .maxStringLength(DashboardWidgetMarker.MAX_JSON_CHARS)
                            .build())
                    .streamWriteConstraints(StreamWriteConstraints.builder()
                            .maxNestingDepth(MAX_NESTING_DEPTH)
                            .build())
                    .build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DashboardWidgetJson() {
    }

    /**
     * A field-based candidate as a tree, via the bounded write-then-read round trip described above.
     * Throws IllegalArgumentException — never a Jackson type, never an Error — for anything the mapper
     * cannot turn into bounded JSON text; the message names {@code dashboard_widget} and never echoes
     * the candidate.
     */
    static JsonNode treeOf(Object candidate) {
        try {
            return MAPPER.readTree(MAPPER.writeValueAsString(candidate));
        } catch (Exception e) {
            throw new IllegalArgumentException("dashboard_widget could not be read");
        }
    }
}
