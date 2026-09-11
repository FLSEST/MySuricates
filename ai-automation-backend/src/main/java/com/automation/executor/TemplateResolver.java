package com.automation.executor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Placeholder substitution for {@code {{ ... }}} templates (previous output, named block outputs,
 * context variables). Stateless companion of {@link ExecutionContext}.
 */
final class TemplateResolver {

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
     * Substitution for shell command lines with mixed trust: trusted values are inserted raw
     * (byte-identical to {@link #resolve}), variables in {@code untrusted} are single-quoted.
     * Untrusted values are substituted last and never re-scanned for placeholders, so an
     * attacker-controlled value can neither break out of its quotes nor pull other variables'
     * raw content into the command.
     */
    static String resolveShell(String template, String previousOutput, Map<String, String> blockOutputs,
                               Map<String, Object> vars, Set<String> untrusted) {
        if (template == null) return null;
        if (untrusted.isEmpty()) return resolve(template, previousOutput, blockOutputs, vars);

        Map<String, Object> trusted = new HashMap<>(vars);
        Map<String, String> deferred = new LinkedHashMap<>();
        String resolved = template;
        int n = 0;
        for (String name : untrusted) {
            trusted.remove(name);
            String token = "\u0000untrusted-" + (n++) + "\u0000";
            String before = resolved;
            resolved = resolved.replace("{{ " + name + " }}", token).replace("{{" + name + "}}", token);
            if (!before.equals(resolved)) {
                deferred.put(token, shellEscape(String.valueOf(vars.get(name))));
            }
        }
        resolved = resolve(resolved, previousOutput, blockOutputs, trusted);
        for (Map.Entry<String, String> entry : deferred.entrySet()) {
            resolved = resolved.replace(entry.getKey(), entry.getValue());
        }
        return resolved;
    }

    static String shellEscape(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
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
