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
    /**
     * {@code {{x}}} or {@code {{ x }}} — exactly the two spellings the resolver has always supported. Keys may
     * contain spaces (block names like "Build Report"); an asymmetric spelling yields a key with a leading or
     * trailing space that matches nothing and stays literal.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{( ?)([^{}]+?)\\1\\}\\}");

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
     * Substitution for shell command lines with mixed trust: every trusted placeholder resolves
     * exactly as in {@link #resolve}, untrusted ones are single-quoted. Untrusted values are
     * substituted last, in a single pass that never re-scans what it inserted, so an
     * attacker-controlled value can neither break out of its quotes nor pull other values' raw
     * content into the command line.
     *
     * <p>The {@link Taint#NONE} shortcut below only skips the bookkeeping, not a behaviour
     * difference. A real workflow run never takes it: {@code previous_report} and {@code open_todos}
     * are always escaped ({@link ExecutionContext#setUntrustedPlatformVariable}), so the taint set
     * is never empty there.
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

    /**
     * One pass over the template: every placeholder is looked up once and the inserted value is never
     * scanned again, so a value that itself contains {@code {{ … }}} (a log, an AI answer) stays literal.
     */
    private static String substitute(String template, String previousOutput, Map<String, String> blockOutputs,
                                     Map<String, Object> vars, boolean escape) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String raw = lookup(matcher.group(2), previousOutput, blockOutputs, vars);
            String replacement = raw == null ? matcher.group() : value(raw, escape);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Value for one placeholder key, or null when unknown (the placeholder then stays literal). */
    private static String lookup(String key, String previousOutput, Map<String, String> blockOutputs,
                                 Map<String, Object> vars) {
        if ("previous_output".equals(key)) return previousOutput != null ? previousOutput : "";
        if (key.startsWith("block.") && key.endsWith(".output") && key.length() > "block..output".length()) {
            String name = key.substring("block.".length(), key.length() - ".output".length());
            if (blockOutputs.containsKey(name)) {
                String output = blockOutputs.get(name);
                return output == null ? "" : output;
            }
        }
        if (vars.containsKey(key)) return String.valueOf(vars.get(key));
        return null;
    }

    private static String value(String raw, boolean escape) {
        return escape ? shellEscape(raw) : raw;
    }
}
