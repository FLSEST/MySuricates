package com.automation.executor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder substitution for {@code {{ ... }}} templates (previous output, named block outputs,
 * context variables). Stateless companion of {@link ExecutionContext}.
 */
final class TemplateResolver {

    private static final Pattern SENTINEL = Pattern.compile("\\x00untrusted-\\d+\\x00");

    private TemplateResolver() {}

    /** Raw substitution — values are inserted verbatim. */
    static String resolve(String template, String previousOutput,
                          Map<String, String> blockOutputs, Map<String, Object> vars) {
        return substitute(template, previousOutput, blockOutputs, vars, false);
    }

    /**
     * Shell-safe substitution: each value is wrapped in POSIX single quotes with internal
     * single quotes escaped as '\'' to prevent shell injection.
     */
    static String resolveShellSafe(String template, String previousOutput,
                                   Map<String, String> blockOutputs, Map<String, Object> vars) {
        return substitute(template, previousOutput, blockOutputs, vars, true);
    }

    /**
     * Which values of a run are untrusted: variable names, named block outputs and whether
     * {@code previous_output} is tainted. {@link #NONE} is a clean run.
     */
    record Taint(Set<String> variables, Set<String> outputs, boolean previousOutput) {

        static final Taint NONE = new Taint(Set.of(), Set.of(), false);

        boolean isEmpty() {
            return variables.isEmpty() && outputs.isEmpty() && !previousOutput;
        }
    }

    /**
     * Substitution for shell command lines with mixed trust: trusted values are inserted raw
     * (byte-identical to {@link #resolve}), untrusted ones single-quoted. Untrusted values are
     * substituted last, in a single pass that never re-scans what it inserted, so an
     * attacker-controlled value can neither break out of its quotes nor pull other values' raw
     * content into the command line.
     */
    static String resolveShell(String template, String previousOutput, Map<String, String> blockOutputs,
                               Map<String, Object> vars, Taint taint) {
        if (template == null) return null;
        if (taint.isEmpty()) return resolve(template, previousOutput, blockOutputs, vars);

        Map<String, Object> trustedVars = new HashMap<>(vars);
        Map<String, String> trustedOutputs = new HashMap<>(blockOutputs);
        Map<String, String> deferred = new LinkedHashMap<>();
        String resolved = template;
        if (taint.previousOutput()) {
            resolved = defer(resolved, deferred, previousOutput == null ? "" : previousOutput,
                    "{{ previous_output }}", "{{previous_output}}");
        }
        for (String name : taint.outputs()) {
            // An unknown name keeps its placeholder (as it does without taint) instead of becoming ''
            if (!blockOutputs.containsKey(name)) continue;
            resolved = defer(resolved, deferred, trustedOutputs.remove(name),
                    "{{ block." + name + ".output }}", "{{block." + name + ".output}}");
        }
        for (String name : taint.variables()) {
            trustedVars.remove(name);
            resolved = defer(resolved, deferred, String.valueOf(vars.get(name)),
                    "{{ " + name + " }}", "{{" + name + "}}");
        }
        resolved = resolve(resolved, previousOutput, trustedOutputs, trustedVars);
        return substituteSentinels(resolved, deferred);
    }

    static String shellEscape(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Replaces every occurrence of {@code placeholders} with one sentinel and stores its escaped value. */
    private static String defer(String template, Map<String, String> deferred, String value, String... placeholders) {
        String token = "\u0000untrusted-" + deferred.size() + "\u0000";
        String resolved = template;
        for (String placeholder : placeholders) {
            resolved = resolved.replace(placeholder, token);
        }
        if (!resolved.equals(template)) deferred.put(token, shellEscape(value));
        return resolved;
    }

    /** One pass over the sentinels: the values it inserts are never scanned again. */
    private static String substituteSentinels(String resolved, Map<String, String> deferred) {
        if (deferred.isEmpty()) return resolved;
        Matcher matcher = SENTINEL.matcher(resolved);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = deferred.get(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String substitute(String template, String previousOutput, Map<String, String> blockOutputs,
                                     Map<String, Object> vars, boolean escape) {
        if (template == null) return null;

        String resolved = template;
        String previous = value(previousOutput != null ? previousOutput : "", escape);
        resolved = resolved.replace("{{ previous_output }}", previous);
        resolved = resolved.replace("{{previous_output}}", previous);

        for (Map.Entry<String, String> entry : blockOutputs.entrySet()) {
            String value = value(entry.getValue() == null ? "" : entry.getValue(), escape);
            resolved = resolved.replace("{{ block." + entry.getKey() + ".output }}", value);
            resolved = resolved.replace("{{block." + entry.getKey() + ".output}}", value);
        }

        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            String value = value(String.valueOf(entry.getValue()), escape);
            resolved = resolved.replace("{{ " + entry.getKey() + " }}", value);
            resolved = resolved.replace("{{" + entry.getKey() + "}}", value);
        }

        return resolved;
    }

    private static String value(String raw, boolean escape) {
        return escape ? shellEscape(raw) : raw;
    }
}
