package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict check of the {@code dashboard_widget:} contract (spec E3). Workflow output is AI-generated
 * text from external sources, so the rule is: anything that does not match the contract exactly is
 * rejected as a whole — the widget then shows its neutral state instead of a half-understood result.
 *
 * <p>The parser does <b>not</b> filter markup. {@code <script>} stays ordinary text inside the
 * contract; it is harmless because the frontend renders contract values only as React text nodes.
 * What the parser does reject is structure it does not know (unknown fields, wrong types), anything
 * over its limits, and text that cannot be trusted to read as it looks. Two different rules apply on
 * purpose here (fix round 2, B1+B2 — round 1's wording, "must contain at least one visible code
 * point", got implemented as "any space separator anywhere is fatal", which is a different and
 * stricter rule that broke ordinary German text such as "12 000", "z. B." or "§ 5"):
 * <ol>
 *   <li><b>Fatal anywhere in the string</b> — rejects the whole contract regardless of what else is
 *       there, unchanged since round 1: control (Cc) and format (Cf, zero width, BOM, bidi overrides
 *       included) characters, line/paragraph separators, lone surrogates, private-use code points.</li>
 *   <li><b>Not visible, but merely excluded from counting</b> — legal anywhere in the string; a
 *       string is valid only if, after trimming, at least one of its code points is neither in this
 *       class nor an ordinary U+0020 space: every {@code SPACE_SEPARATOR} other than U+0020 (so a
 *       non-breaking, figure or narrow no-break space no longer breaks a thousands separator or an
 *       abbreviation), the known invisible filler letters/symbols (U+2800, U+3164, U+115F, U+1160,
 *       U+FFA0), the non-Cf default-ignorable code points (U+034F, U+17B4–U+17B5, U+180B–U+180F,
 *       U+FE00–U+FE0F, U+E0100–U+E01EF — a lone variation selector or vowel-shaping mark without a
 *       base character; most of these are category Mn, not Cf, so plain {@code Character.getType}
 *       classification alone does not catch them), and unassigned code points.</li>
 * </ol>
 *
 * <p>No numbers exist in the contract: a KPI value is a string, so {@code NaN}, {@code Infinity} or
 * {@code 1e400} can never reach a widget — a number where a string belongs is simply a violation.
 *
 * <p>Accepted as a residual risk, bounded by the length caps, because real i18n text needs them and
 * they cannot be told apart from hostile use by code point alone: combining-mark floods ("Zalgo" —
 * ordinary combining marks outside the specific default-ignorable ranges above count as visible), and
 * a variation selector or strong-RTL letter attached to a visible base character (an emoji plus its
 * presentation selector, e.g. {@code "✅️"}, stays legal because the emoji itself is visible).
 *
 * <p>Stateless utility, like {@code GateStatusOutputParser}: no Spring bean, and its own mapper setup
 * ({@link DashboardWidgetJson} — two mappers behind methods, never a field, since this task's fix
 * round 2, H3) so Spring's Jackson customisation can never change what counts as valid. Pinned
 * explicitly rather than relying on Jackson defaults (checked against the project's pinned Jackson
 * 2.18.5): duplicate keys are rejected, and both stream-read and stream-write constraints (nesting
 * depth, string length) are capped so a pathological payload throws a normal, caught exception instead
 * of risking a {@link StackOverflowError} that {@code catch (Exception e)} would not stop —
 * {@code writeValueAsString} itself stays uncapped in size (this task's fix round 2, H3): a single very
 * large value is fully serialized before the contract's own per-field cap rejects it, a deliberate,
 * documented limit rather than a gap, since the caller's Java object already held that data in memory
 * before conversion began. {@code FAIL_ON_TRAILING_TOKENS} cannot fire today — {@link
 * DashboardWidgetMarker} hands {@link #parse} exactly one balanced-brace substring — it is kept as
 * defence-in-depth in case a future caller feeds the mapper raw, unscanned text.
 */
public final class DashboardWidgetResultParser {

    /** Run metadata key holding the validated contract of the run. */
    public static final String METADATA_KEY = "dashboard_widget";

    /**
     * Run metadata key set to the fixed token {@code "INVALID"} when a marker was found but rejected.
     * A fixed token on purpose: nothing from the output is ever echoed back into the metadata.
     */
    public static final String ERROR_METADATA_KEY = "dashboard_widget_error";

    static final int MAX_TITLE = 80;
    static final int MAX_KPIS = 6;
    static final int MAX_KPI_TEXT = 40;
    static final int MAX_ITEMS = 10;
    static final int MAX_ITEM = 200;
    static final int MAX_SUMMARY = 500;

    private static final Set<String> RESULT_FIELDS = Set.of("title", "status", "kpis", "items", "summary");
    private static final Set<String> KPI_FIELDS = Set.of("label", "value", "trend");

    /** VALID carries the result, MISSING means "no marker", INVALID means "marker, but not the contract". */
    public record ParseResult(ResultState state, Result result) {
        static final ParseResult MISSING = new ParseResult(ResultState.MISSING, null);
        static final ParseResult INVALID = new ParseResult(ResultState.INVALID, null);
    }

    private DashboardWidgetResultParser() {
    }

    /**
     * The contract of a whole run: the last output carrying a marker decides, because a report block
     * usually runs last. Callers pass the outputs in step order and leave out derived ones. A null
     * list (no outputs to look at) is simply MISSING, not a crash.
     */
    public static ParseResult parseRun(List<String> outputs) {
        if (outputs == null) {
            return ParseResult.MISSING;
        }
        ParseResult result = ParseResult.MISSING;
        for (String output : outputs) {
            ParseResult parsed = parse(output);
            if (parsed.state() != ResultState.MISSING) {
                result = parsed;
            }
        }
        return result;
    }

    /**
     * One output's contract. A marker occurring more than once in this single output is rejected
     * outright as ambiguous (fix round 1, A2) — see {@link DashboardWidgetMarker}.
     */
    public static ParseResult parse(String output) {
        DashboardWidgetMarker.Scan scan = DashboardWidgetMarker.scan(output);
        if (!scan.found()) {
            return ParseResult.MISSING;
        }
        if (scan.ambiguous() || scan.json() == null) {
            return ParseResult.INVALID;
        }
        try {
            return new ParseResult(ResultState.VALID, validate(DashboardWidgetJson.markerTreeOf(scan.json())));
        } catch (Exception e) {
            return ParseResult.INVALID;
        }
    }

    /** Re-checks what a run's metadata holds: stored data is validated again on its way out. */
    public static ParseResult fromMetadata(Object stored) {
        if (stored == null) {
            return ParseResult.MISSING;
        }
        try {
            return new ParseResult(ResultState.VALID, validate(DashboardWidgetJson.fieldTreeOf(stored)));
        } catch (Exception e) {
            return ParseResult.INVALID;
        }
    }

    /** The plain map that goes into the run metadata (stored as JSON by JsonMapConverter). */
    public static Map<String, Object> toMetadata(Result result) {
        if (result == null) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", result.status().name());
        if (result.title() != null) {
            map.put("title", result.title());
        }
        if (!result.kpis().isEmpty()) {
            map.put("kpis", result.kpis().stream().map(kpi -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("label", kpi.label());
                entry.put("value", kpi.value());
                if (kpi.trend() != null) {
                    entry.put("trend", kpi.trend().name());
                }
                return entry;
            }).toList());
        }
        if (!result.items().isEmpty()) {
            map.put("items", result.items());
        }
        if (result.summary() != null) {
            map.put("summary", result.summary());
        }
        return map;
    }

    /**
     * The same check for a caller that already holds the contract as fields instead of as marker text
     * (the {@code dashboard-report} block, and the editor assist after {@link #parse}): the private
     * {@link #validate} of the marker path, only with the exception passed on instead of swallowed.
     * Messages name the offending field and never echo its value.
     */
    public static Result validateOrThrow(Object candidate) {
        return validate(DashboardWidgetJson.fieldTreeOf(candidate));
    }

    /** Throws IllegalArgumentException for anything outside the contract; messages never echo values. */
    private static Result validate(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("dashboard_widget must be an object");
        }
        requireOnly(node, RESULT_FIELDS, "dashboard_widget");
        return new Result(
                text(node.get("title"), MAX_TITLE, false, "title"),
                enumValue(node.get("status"), Result.Status.class, true, "status"),
                kpis(node.get("kpis")),
                items(node.get("items")),
                text(node.get("summary"), MAX_SUMMARY, false, "summary"));
    }

    private static void requireOnly(JsonNode node, Set<String> allowed, String path) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(path + " has an unknown field");
            }
        });
    }

    private static List<Result.Kpi> kpis(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_KPIS) {
            throw new IllegalArgumentException("kpis must be an array with at most " + MAX_KPIS + " entries");
        }
        List<Result.Kpi> kpis = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode entry = node.get(i);
            String at = "kpis[" + (i + 1) + "]";
            if (!entry.isObject()) {
                throw new IllegalArgumentException(at + " must be an object");
            }
            requireOnly(entry, KPI_FIELDS, at);
            kpis.add(new Result.Kpi(
                    text(entry.get("label"), MAX_KPI_TEXT, true, at + ".label"),
                    text(entry.get("value"), MAX_KPI_TEXT, true, at + ".value"),
                    enumValue(entry.get("trend"), Result.Trend.class, false, at + ".trend")));
        }
        return List.copyOf(kpis);
    }

    private static List<String> items(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("items must be an array with at most " + MAX_ITEMS + " entries");
        }
        List<String> items = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            items.add(text(node.get(i), MAX_ITEM, true, "items[" + (i + 1) + "]"));
        }
        return List.copyOf(items);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, boolean required, String field) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        try {
            return Enum.valueOf(type, node.textValue().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is outside the contract");
        }
    }

    /**
     * A contract string: trimmed, non-empty, within its cap, free of fatal characters, and (fix
     * round 2, B1+B2) with at least one visible code point remaining — see the class javadoc.
     */
    private static String text(JsonNode node, int max, boolean required, String field) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String value = node.textValue().strip();
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(field + " is empty or longer than " + max + " characters");
        }
        if (value.codePoints().anyMatch(DashboardWidgetTextRule::isFatal)) {
            throw new IllegalArgumentException(field + " holds a control, format or private-use character");
        }
        if (value.codePoints().noneMatch(DashboardWidgetTextRule::isVisible)) {
            throw new IllegalArgumentException(field + " has no visible content");
        }
        return value;
    }
}
