package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import com.automation.executor.PlaceholderSyntax;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block config of a {@code dashboard-report} block → the candidate map that
 * {@code DashboardWidgetResultParser.validateOrThrow} checks.
 *
 * <p>Three things happen here and nothing else: {@code {{ … }}} placeholders are resolved, values
 * that resolved to nothing are dropped, and a placeholder that stayed unresolved is reported. Every
 * limit, every character rule, every enum and every structural rule (required, must be a list, must
 * be an object) lives in the parser — this class decides no contract question, so there is no second
 * truth about what a legal report looks like. Non-string top-level and KPI values are passed through
 * unchanged for the same reason: forcing them to a string here would hide "{@code title} must be a
 * string" behind a stringified map.
 *
 * <p><b>Dropping blanks is deliberate.</b> Optional placeholders are the normal case in a workflow;
 * if an empty {@code {{ subtitle }}} were fatal, every workflow would have to guarantee every
 * variable. A missing or blank {@code status} is simply left out too — the parser is the one place
 * that says it is required, and says so with the same message regardless of which layer it was
 * missing from.
 *
 * <p><b>An unresolved placeholder is fatal, decided on the template.</b> {@code TemplateResolver}
 * leaves an unknown placeholder literally in place, so without this check the dashboard would show
 * {@code {{ hunt_count }}} as a KPI. The check runs on the raw config text via
 * {@link PlaceholderSyntax#keysIn} — the one shared definition of the {@code {{ … }}} form — never on
 * the already-substituted result: a variable whose own value happens to contain {@code {{ … }}}
 * (ordinary shell or AI output) is not a template author's mistake.
 *
 * <p><b>A config key never becomes message text.</b> A KPI object's keys are free text from the block
 * config (not the contract), and a failed step's message is step output — read again at run end by
 * every marker parser. Resolving a KPI field therefore never quotes the field's own key, only the
 * three known contract field names ({@code label}/{@code value}/{@code trend}) or the bare
 * {@code kpis[n]} position; an unrecognized key is passed to the parser exactly like a non-object
 * entry or a non-list {@code kpis} — as itself, for the parser to reject on its own terms.
 */
final class DashboardReportCandidate {

    /** The three text-shaped contract fields; {@code kpis}/{@code items} are lists, handled separately. */
    private static final List<String> TEXT_FIELDS = List.of("status", "title", "summary");

    /** The only KPI keys a resolution failure may ever name — never a config-supplied key. */
    private static final Set<String> KPI_FIELDS = Set.of("label", "value", "trend");

    private DashboardReportCandidate() {
    }

    /**
     * @throws IllegalArgumentException naming the field (1-based inside lists), never a config key or
     *                                   a candidate value
     */
    static Map<String, Object> build(ExecutionContext context, Map<String, Object> config) {
        Map<String, Object> source = config == null ? Map.of() : config;
        Map<String, Object> candidate = new LinkedHashMap<>();
        for (String field : TEXT_FIELDS) {
            Object value = textField(context, source.get(field), field);
            if (value != null) {
                candidate.put(field, value);
            }
        }
        list(candidate, "kpis", context, source.get("kpis"), true);
        list(candidate, "items", context, source.get("items"), false);
        return candidate;
    }

    /**
     * A top-level text field: resolved and stripped if it is a string, passed through unchanged
     * otherwise — exactly what a KPI field already does, so the parser can say "{@code <field>} must
     * be a string" instead of seeing a stringified map.
     */
    private static Object textField(ExecutionContext context, Object raw, String field) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String template)) {
            return raw;
        }
        return resolvedText(context, template, field);
    }

    /**
     * Resolved, stripped value of one template string — or null once it resolves to nothing.
     *
     * <p>Decides on the <b>template</b>: every placeholder key the raw text actually contains must
     * resolve, or {@code field} is reported. What {@link ExecutionContext#resolveTemplate} returns is
     * used as-is and never scanned again.
     */
    private static String resolvedText(ExecutionContext context, String template, String field) {
        for (String key : PlaceholderSyntax.keysIn(template)) {
            if (!resolves(context, key)) {
                throw new IllegalArgumentException(field + " still holds an unresolved placeholder");
            }
        }
        String value = context.resolveTemplate(template).strip();
        return value.isEmpty() ? null : value;
    }

    /** Whether a placeholder key would resolve — the same three cases {@code TemplateResolver} knows, via its public surface. */
    private static boolean resolves(ExecutionContext context, String key) {
        if (ExecutionContext.PREVIOUS_OUTPUT_NAME.equals(key)) {
            return true;
        }
        if (key.startsWith("block.") && key.endsWith(".output") && key.length() > "block..output".length()) {
            String name = key.substring("block.".length(), key.length() - ".output".length());
            return context.getBlockOutput(name) != null;
        }
        return context.getVariable(key) != null;
    }

    /**
     * {@code kpis} or {@code items}: not a list at all is passed through unchanged for the parser to
     * reject ("{@code <field>} must be an array…", its own rule, not duplicated here); a list is
     * resolved entry by entry and, if it ends up empty, simply left out like any other optional field.
     */
    private static void list(Map<String, Object> candidate, String field, ExecutionContext context,
                              Object raw, boolean kpi) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> entries)) {
            candidate.put(field, raw);
            return;
        }
        List<Object> resolved = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            String at = field + "[" + (i + 1) + "]";
            if (kpi) {
                // Not an object at all: pass through as-is, same reasoning as the field-level check above.
                Object value = entry instanceof Map<?, ?> map ? kpiFields(context, map, at) : entry;
                if (value != null) {
                    resolved.add(value);
                }
            } else if (entry != null) {
                String value = resolvedText(context, entry.toString(), at);
                if (value != null) {
                    resolved.add(value);
                }
            }
        }
        if (!resolved.isEmpty()) {
            candidate.put(field, resolved);
        }
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
            Object value = field.getValue();
            String path = KPI_FIELDS.contains(key) ? at + "." + key : at;
            kpi.put(key, value instanceof String text ? resolvedText(context, text, path) : value);
        }
        kpi.values().removeIf(java.util.Objects::isNull);
        return kpi.isEmpty() ? null : kpi;
    }
}
