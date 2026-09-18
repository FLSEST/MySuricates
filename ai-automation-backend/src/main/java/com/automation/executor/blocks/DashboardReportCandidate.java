package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Block config of a {@code dashboard-report} block → the candidate map that
 * {@code DashboardWidgetResultParser.validateOrThrow} checks.
 *
 * <p>Three things happen here and nothing else: {@code {{ … }}} placeholders are resolved, values
 * that resolved to nothing are dropped, and a placeholder that stayed unresolved is reported. Every
 * limit, every character rule and every enum lives in the parser — this class decides no contract
 * question, so there is no second truth about what a legal report looks like.
 *
 * <p><b>Dropping blanks is deliberate.</b> Optional placeholders are the normal case in a workflow;
 * if an empty {@code {{ subtitle }}} were fatal, every workflow would have to guarantee every
 * variable. {@code status} is the exception: without it there is no contract.
 *
 * <p><b>An unresolved placeholder is fatal.</b> {@code TemplateResolver} leaves an unknown
 * placeholder literally in place, so without this check the dashboard would show
 * {@code {{ hunt_count }}} as a KPI. Loud failure beats quiet nonsense.
 *
 * <p><b>Unknown keys: ignored at the top, passed through inside a KPI.</b> The block config is a
 * platform surface that legitimately carries platform keys ({@code timeout_seconds}, leftovers of a
 * previous block type); a KPI object is pure contract, so a typo there must reach the validator.
 */
final class DashboardReportCandidate {

    /** The five contract fields, in contract order. Everything else on this level is not ours. */
    private static final List<String> TEXT_FIELDS = List.of("status", "title", "summary");

    /** Same shape TemplateResolver looks for — a placeholder still here never resolved. */
    private static final Pattern UNRESOLVED = Pattern.compile("\\{\\{\\s*[^}]*}}");

    private DashboardReportCandidate() {
    }

    /**
     * @throws IllegalArgumentException naming the field (1-based inside lists), never its value
     */
    static Map<String, Object> build(ExecutionContext context, Map<String, Object> config) {
        Map<String, Object> source = config == null ? Map.of() : config;
        Map<String, Object> candidate = new LinkedHashMap<>();

        String status = resolved(context, source.get("status"), "status");
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        candidate.put("status", status);
        for (String field : TEXT_FIELDS) {
            if (field.equals("status")) {
                continue;
            }
            String value = resolved(context, source.get(field), field);
            if (value != null) {
                candidate.put(field, value);
            }
        }
        List<Object> kpis = kpis(context, source.get("kpis"));
        if (!kpis.isEmpty()) {
            candidate.put("kpis", kpis);
        }
        List<Object> items = items(context, source.get("items"));
        if (!items.isEmpty()) {
            candidate.put("items", items);
        }
        return candidate;
    }

    /** Resolved, stripped value — or null when there was nothing or it resolved to nothing. */
    private static String resolved(ExecutionContext context, Object raw, String field) {
        if (raw == null) {
            return null;
        }
        String value = context.resolveTemplate(raw.toString());
        if (UNRESOLVED.matcher(value).find()) {
            throw new IllegalArgumentException(field + " still holds an unresolved placeholder");
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static List<Object> items(ExecutionContext context, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("items must be a list");
        }
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String value = resolved(context, list.get(i), "items[" + (i + 1) + "]");
            if (value != null) {
                items.add(value);
            }
        }
        return items;
    }

    private static List<Object> kpis(ExecutionContext context, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("kpis must be a list");
        }
        List<Object> kpis = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String at = "kpis[" + (i + 1) + "]";
            if (!(list.get(i) instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException(at + " must be an object");
            }
            Map<String, Object> kpi = kpi(context, entry, at);
            if (kpi != null) {
                kpis.add(kpi);
            }
        }
        return kpis;
    }

    /**
     * Null only for a KPI whose every value resolved to nothing — that is the freshly added, still
     * empty row of the editor form. Anything else is passed on as it is, so the validator gets to
     * complain about a half-filled row ({@code kpis[1].value is required}) and about a typo in a key
     * ({@code kpis[1] has an unknown field}). Dropping a row because one of its two fields is empty
     * would swallow exactly that typo.
     */
    private static Map<String, Object> kpi(ExecutionContext context, Map<?, ?> entry, String at) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : entry.entrySet()) {
            String key = String.valueOf(field.getKey());
            Object value = field.getValue();
            // Non-strings stay as they are so the validator can reject them with its own message.
            kpi.put(key, value instanceof String text ? resolved(context, text, at + "." + key) : value);
        }
        kpi.values().removeIf(java.util.Objects::isNull);
        return kpi.isEmpty() ? null : kpi;
    }
}
