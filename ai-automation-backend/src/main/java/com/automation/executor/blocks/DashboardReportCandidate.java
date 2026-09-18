package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import com.automation.executor.PlaceholderSyntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block config of a {@code dashboard-report} block → the candidate map that
 * {@code DashboardWidgetResultParser.validateOrThrow} checks.
 *
 * <p>Three things happen here and nothing else: {@code {{ … }}} placeholders are resolved, values
 * that resolved to nothing are dropped, and a placeholder that stayed unresolved is reported. Every
 * limit, every character rule, every enum and every structural rule (required, must be a list, must
 * be an object) lives in the parser — this class decides no contract question, so there is no second
 * truth about what a legal report looks like. Non-string values (top level, inside a KPI, and inside
 * {@code items}) are passed through unchanged for the same reason: forcing them to a string here
 * would hide "{@code title} must be a string" behind a stringified map.
 *
 * <p><b>Dropping blanks is deliberate.</b> Optional placeholders are the normal case in a workflow;
 * if an empty {@code {{ subtitle }}} were fatal, every workflow would have to guarantee every
 * variable. A missing or blank {@code status} is simply left out too — the parser is the one place
 * that says it is required. A value that is present but simply not usable (a non-object
 * {@code kpis} entry, a raw {@code null}) is never dropped — only passed through, so the parser still
 * gets to reject it; whether an entry survives here must never be the reason the run's log holds one
 * outcome and the marker path would have held another.
 *
 * <p><b>An unresolved placeholder is fatal, decided on the template, asked of the context — never
 * rebuilt.</b> {@code TemplateResolver} leaves an unknown placeholder literally in place, so without
 * this check the dashboard would show {@code {{ hunt_count }}} as a KPI. Which keys the raw text
 * contains comes from {@link PlaceholderSyntax#keysIn} — the one shared definition of the
 * {@code {{ … }}} form; whether a key would actually resolve is answered by
 * {@link ExecutionContext#resolvesPlaceholder}, not re-derived here — a hand copy of that lookup
 * already drifted from it once (a defined variable holding {@code null}, and the
 * {@code block.<name>.output} fallback to a same-named variable). The check never runs on the
 * already-substituted result either: a variable whose own value happens to contain {@code {{ … }}}
 * (ordinary shell or AI output) is not a template author's mistake.
 *
 * <p><b>A config key never becomes message text.</b> A KPI object's keys are free text from the block
 * config (not the contract), and a failed step's message is step output — read again at run end by
 * every marker parser. Resolving a KPI field therefore never quotes the field's own key, only the
 * three known contract field names ({@code label}/{@code value}/{@code trend}) or the bare
 * {@code kpis[n]} position; an unrecognized key is passed to the parser exactly like a non-object
 * entry or a non-list {@code kpis} — as itself, for the parser to reject on its own terms.
 *
 * <p><b>A validator message is renumbered back to what the author wrote.</b> Dropping a blank row
 * shifts every later survivor's position in the list the parser sees, so the parser's own
 * {@code kpis[2]} may really be the config's fourth entry. {@link #buildTracked} records, per
 * surviving entry, which position it came from; {@link #renumber} rewrites a parser message with it
 * before the executor shows it — the one place a run-facing message is produced, not duplicated.
 */
final class DashboardReportCandidate {

    /** The three text-shaped contract fields; {@code kpis}/{@code items} are lists, handled separately. */
    private static final List<String> TEXT_FIELDS = List.of("status", "title", "summary");

    /** The only KPI keys a resolution failure may ever name — never a config-supplied key. */
    private static final Set<String> KPI_FIELDS = Set.of("label", "value", "trend");

    /** What {@link #renumber} looks for: a list field name plus the parser's 1-based position. */
    private static final Pattern INDEXED_FIELD = Pattern.compile("(kpis|items)\\[(\\d+)\\]");

    /**
     * The built candidate plus, per list field ({@code "kpis"}/{@code "items"}), the original
     * 1-based config position of each surviving entry in order — {@link #renumber}'s input.
     */
    record Built(Map<String, Object> candidate, Map<String, List<Integer>> positions) {
    }

    private DashboardReportCandidate() {
    }

    /** Convenience for callers that only need the candidate — see {@link #buildTracked}. */
    static Map<String, Object> build(ExecutionContext context, Map<String, Object> config) {
        return buildTracked(context, config).candidate();
    }

    /**
     * @throws IllegalArgumentException naming the field (1-based inside lists, the config's own
     *                                   position — nothing here has been dropped yet), never a config
     *                                   key or a candidate value
     */
    static Built buildTracked(ExecutionContext context, Map<String, Object> config) {
        Map<String, Object> source = config == null ? Map.of() : config;
        Map<String, Object> candidate = new LinkedHashMap<>();
        for (String field : TEXT_FIELDS) {
            Object value = resolvedOrPassThrough(context, source.get(field), field);
            if (value != null) {
                candidate.put(field, value);
            }
        }
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        positions.put("kpis", list(candidate, "kpis", context, source.get("kpis"), true));
        positions.put("items", list(candidate, "items", context, source.get("items"), false));
        return new Built(candidate, positions);
    }

    /**
     * Rewrites every {@code kpis[n]}/{@code items[n]} in a parser message from the parser's post-drop
     * position to the config position {@code positions} recorded for it. An index outside what was
     * recorded (a message that did not come from validating a built candidate) is left as it is.
     */
    static String renumber(String message, Map<String, List<Integer>> positions) {
        if (message == null) {
            return null;
        }
        Matcher matcher = INDEXED_FIELD.matcher(message);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            List<Integer> survivors = positions.get(matcher.group(1));
            int parserIndex = Integer.parseInt(matcher.group(2));
            int original = survivors != null && parserIndex >= 1 && parserIndex <= survivors.size()
                    ? survivors.get(parserIndex - 1) : parserIndex;
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + "[" + original + "]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** A candidate value: resolved and stripped if it is a string template, passed through unchanged otherwise. */
    private static Object resolvedOrPassThrough(ExecutionContext context, Object raw, String field) {
        if (raw == null) {
            return null;
        }
        return raw instanceof String template ? resolvedText(context, template, field) : raw;
    }

    /**
     * Resolved, stripped value of one template string — or null once it resolves to nothing.
     *
     * <p>Decides on the <b>template</b>: every placeholder key the raw text actually contains must
     * resolve ({@link ExecutionContext#resolvesPlaceholder}), or {@code field} is reported. What
     * {@link ExecutionContext#resolveTemplate} returns is used as-is and never scanned again.
     */
    private static String resolvedText(ExecutionContext context, String template, String field) {
        for (String key : PlaceholderSyntax.keysIn(template)) {
            if (!context.resolvesPlaceholder(key)) {
                throw new IllegalArgumentException(field + " still holds an unresolved placeholder");
            }
        }
        String value = context.resolveTemplate(template).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * {@code kpis} or {@code items}: not a list at all is passed through unchanged for the parser to
     * reject ("{@code <field>} must be an array…", its own rule, not duplicated here); a list is
     * resolved entry by entry. A KPI entry that is not an object (including a bare {@code null}) is
     * passed through unchanged too, for the same reason — dropping it would silently accept an input
     * the marker path rejects. Only an entry that resolved to genuinely nothing (a blank string item,
     * or a KPI object whose every value resolved to nothing — the editor's still-empty row) is left
     * out, exactly like any other optional field; its original config position is not recorded either,
     * since nothing about it reaches the parser to be misnumbered.
     *
     * @return the original 1-based config position of each entry that made it into the candidate, in
     *         the same order — {@link #renumber}'s lookup table for this field
     */
    private static List<Integer> list(Map<String, Object> candidate, String field, ExecutionContext context,
                                       Object raw, boolean kpi) {
        List<Integer> positions = new ArrayList<>();
        if (raw == null) {
            return positions;
        }
        if (!(raw instanceof List<?> entries)) {
            candidate.put(field, raw);
            return positions;
        }
        List<Object> resolved = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            String at = field + "[" + (i + 1) + "]";
            Object value;
            boolean keep;
            if (kpi && entry instanceof Map<?, ?> map) {
                value = kpiFields(context, map, at);
                keep = value != null; // drop only a fully-blank KPI row (the editor's still-empty one)
            } else if (kpi) {
                value = entry; // not an object at all, including a bare null — the parser rejects it, never silently dropped
                keep = true;
            } else if (entry == null) {
                value = null;
                keep = false; // a null items entry stays silently absent, unchanged from before
            } else {
                value = resolvedOrPassThrough(context, entry, at);
                keep = value != null; // a string that resolved to nothing is dropped; anything else always survives
            }
            if (keep) {
                resolved.add(value);
                positions.add(i + 1);
            }
        }
        if (!resolved.isEmpty()) {
            candidate.put(field, resolved);
        }
        return positions;
    }

    /**
     * Null only for a KPI object whose every value resolved to nothing — the editor's freshly added,
     * still empty row. Anything else is passed on as it is, so the parser gets to complain about a
     * half-filled row ({@code kpis[1].value is required}) and about a typo in a key
     * ({@code kpis[1] has an unknown field}) — see the class javadoc for why the field's own key never
     * reaches a resolution-failure message.
     */
    private static Map<String, Object> kpiFields(ExecutionContext context, Map<?, ?> entry, String at) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : entry.entrySet()) {
            String key = String.valueOf(field.getKey());
            String path = KPI_FIELDS.contains(key) ? at + "." + key : at;
            kpi.put(key, resolvedOrPassThrough(context, field.getValue(), path));
        }
        kpi.values().removeIf(java.util.Objects::isNull);
        return kpi.isEmpty() ? null : kpi;
    }
}
