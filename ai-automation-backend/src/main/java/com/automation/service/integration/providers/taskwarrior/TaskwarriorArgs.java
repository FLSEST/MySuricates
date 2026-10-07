package com.automation.service.integration.providers.taskwarrior;

import com.automation.service.integration.providers.IntegrationParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Argument-building and parameter-validation helpers for {@link TaskwarriorProvider} — pure
 * functions, no I/O. Kept apart from the provider, which owns the CLI calls and action dispatch.
 */
final class TaskwarriorArgs {

    private TaskwarriorArgs() {}

    /** Accepts Taskwarrior UUIDs (hex + dashes) and short numeric ids. */
    private static final Pattern ID_PATTERN = Pattern.compile("^[0-9a-fA-F-]+$");

    /**
     * Taskwarrior command keywords that must never appear in a caller-supplied filter, since
     * Taskwarrior scans the whole command line for a command and the first match wins —
     * {@code execute} would run an arbitrary host command. Compared case-insensitively, exact-token.
     */
    private static final Set<String> FORBIDDEN_FILTER_TOKENS = Set.of(
            "execute", "import", "config", "undo", "purge", "edit", "sync", "context",
            "add", "delete", "done", "modify", "annotate", "denotate", "duplicate", "log"
    );

    private static final Set<String> VALID_PRIORITIES = Set.of("H", "M", "L");

    /** Builds {@code project:} / {@code priority:} / {@code due:} / {@code +tag} attribute args. */
    static List<String> buildAttributeArgs(Map<String, Object> params) {
        List<String> args = new ArrayList<>();

        if (params.containsKey("project")) {
            String p = paramString(params, "project", "").trim();
            if (!p.isEmpty()) {
                args.add("project:" + p);
            }
        }
        if (params.containsKey("priority")) {
            String pr = paramString(params, "priority", "").trim().toUpperCase(Locale.ROOT);
            if (!pr.isEmpty()) {
                if (!VALID_PRIORITIES.contains(pr)) {
                    throw new IllegalArgumentException("Invalid priority: " + pr + " (expected H, M or L)");
                }
                args.add("priority:" + pr);
            }
        }
        if (params.containsKey("due")) {
            String d = paramString(params, "due", "").trim();
            if (!d.isEmpty()) {
                args.add("due:" + d);
            }
        }
        for (String tag : extractTags(params.get("tags"))) {
            args.add("+" + tag);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    static List<String> extractTags(Object tagsObj) {
        List<String> tags = new ArrayList<>();
        if (tagsObj instanceof List<?> list) {
            for (Object t : list) {
                addTag(tags, String.valueOf(t));
            }
        } else if (tagsObj instanceof String s) {
            for (String t : s.split(",")) {
                addTag(tags, t);
            }
        }
        return tags;
    }

    static void addTag(List<String> tags, String raw) {
        String tag = raw.trim();
        if (tag.startsWith("+") || tag.startsWith("-")) {
            tag = tag.substring(1).trim();
        }
        if (!tag.isEmpty()) {
            // Tags can't contain whitespace in Taskwarrior; reject to avoid silent arg splitting surprises.
            if (tag.matches(".*\\s.*")) {
                throw new IllegalArgumentException("Invalid tag (whitespace not allowed): " + raw);
            }
            tags.add(tag);
        }
    }

    /**
     * Splits a free-text filter into tokens and rejects anything that could be reinterpreted as a
     * Taskwarrior command or an {@code rc.} override.
     */
    static List<String> sanitizeFilterTokens(String filter) {
        List<String> tokens = new ArrayList<>();
        if (filter == null || filter.isBlank()) {
            return tokens;
        }
        for (String token : filter.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("rc.") || token.startsWith("rc:")) {
                throw new IllegalArgumentException("Disallowed filter token (rc. overrides not permitted): " + token);
            }
            if (FORBIDDEN_FILTER_TOKENS.contains(token.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Disallowed filter token (Taskwarrior command keyword): " + token);
            }
            tokens.add(token);
        }
        return tokens;
    }

    static Map<String, String> envFrom(String combinedToken) {
        Map<String, String> env = new HashMap<>();
        if (combinedToken != null && !combinedToken.isBlank()) {
            String[] parts = combinedToken.split("\\|", 2);
            if (parts.length >= 1 && !parts[0].isBlank()) {
                env.put("TASKDATA", parts[0].trim());
            }
            if (parts.length >= 2 && !parts[1].isBlank()) {
                env.put("TASKRC", parts[1].trim());
            }
        }
        return env;
    }

    static String requireId(Map<String, Object> params) {
        String id = requireParam(params, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid task id: " + id + " (expected UUID or numeric id)");
        }
        return id;
    }

    static int parseLeadingInt(String s) {
        if (s == null) {
            return 0;
        }
        String trimmed = s.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Integer.parseInt(trimmed.substring(0, end));
    }

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        return IntegrationParams.paramString(params, key, defaultValue);
    }

    static String requireParam(Map<String, Object> params, String key) {
        return IntegrationParams.requireNonBlank(params, key);
    }
}
