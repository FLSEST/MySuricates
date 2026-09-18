package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

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
 * over its limits, and characters that make text read differently than it looks (control, format,
 * separator, surrogate and private-use code points — zero width and bidi overrides included).
 *
 * <p>No numbers exist in the contract: a KPI value is a string, so {@code NaN}, {@code Infinity} or
 * {@code 1e400} can never reach a widget — a number where a string belongs is simply a violation.
 *
 * <p>Stateless utility, like {@code GateStatusOutputParser}: no Spring bean, and its own JsonMapper
 * so Spring's Jackson customisation can never change what counts as valid.
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

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
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
     * usually runs last. Callers pass the outputs in step order and leave out derived ones.
     */
    public static ParseResult parseRun(List<String> outputs) {
        ParseResult result = ParseResult.MISSING;
        for (String output : outputs) {
            ParseResult parsed = parse(output);
            if (parsed.state() != ResultState.MISSING) {
                result = parsed;
            }
        }
        return result;
    }

    public static ParseResult parse(String output) {
        DashboardWidgetMarker.Scan scan = DashboardWidgetMarker.scan(output);
        if (!scan.found()) {
            return ParseResult.MISSING;
        }
        if (scan.json() == null) {
            return ParseResult.INVALID;
        }
        try {
            return new ParseResult(ResultState.VALID, validate(MAPPER.readTree(scan.json())));
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
            return new ParseResult(ResultState.VALID, validate(MAPPER.valueToTree(stored)));
        } catch (Exception e) {
            return ParseResult.INVALID;
        }
    }

    /** The plain map that goes into the run metadata (stored as JSON by JsonMapConverter). */
    public static Map<String, Object> toMetadata(Result result) {
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

    /** Throws IllegalArgumentException for anything outside the contract; messages never echo values. */
    private static Result validate(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("dashboard_widget must be an object");
        }
        requireOnly(node, RESULT_FIELDS, "dashboard_widget");
        return new Result(
                text(node.get("title"), MAX_TITLE, false),
                enumValue(node.get("status"), Result.Status.class, true),
                kpis(node.get("kpis")),
                items(node.get("items")),
                text(node.get("summary"), MAX_SUMMARY, false));
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
        for (JsonNode entry : node) {
            if (!entry.isObject()) {
                throw new IllegalArgumentException("a kpi must be an object");
            }
            requireOnly(entry, KPI_FIELDS, "kpi");
            kpis.add(new Result.Kpi(
                    text(entry.get("label"), MAX_KPI_TEXT, true),
                    text(entry.get("value"), MAX_KPI_TEXT, true),
                    enumValue(entry.get("trend"), Result.Trend.class, false)));
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
        for (JsonNode entry : node) {
            items.add(text(entry, MAX_ITEM, true));
        }
        return List.copyOf(items);
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException("a required enum value is missing");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("an enum value must be a string");
        }
        try {
            return Enum.valueOf(type, node.textValue().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("an enum value is outside the contract");
        }
    }

    /** A contract string: trimmed, non-empty, within its cap and free of invisible characters. */
    private static String text(JsonNode node, int max, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw new IllegalArgumentException("a required text is missing");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("a contract value must be a string");
        }
        String value = node.textValue().strip();
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException("a text is empty or longer than " + max + " characters");
        }
        if (value.codePoints().anyMatch(DashboardWidgetResultParser::isInvisible)) {
            throw new IllegalArgumentException("a text holds control or invisible characters");
        }
        return value;
    }

    /**
     * Control characters, invisible format characters (zero width, BOM, bidi overrides), line and
     * paragraph separators, lone surrogates and private-use code points. Contract values are single
     * lines that must read exactly as they look.
     */
    private static boolean isInvisible(int codePoint) {
        if (Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                || type == Character.PRIVATE_USE;
    }
}
