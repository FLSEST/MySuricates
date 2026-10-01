package com.automation.service.integration.providers.gitlab;

import com.automation.service.integration.providers.IntegrationParams;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Parameter parsing shared by the GitLab actions — pure functions, no I/O. */
final class GitLabParams {

    /** GitLab caps {@code per_page} at 100. */
    static final int MAX_PER_PAGE = 100;

    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*");

    private GitLabParams() {}

    /**
     * Project reference as the API expects it in a path: a numeric ID stays as it is, a path
     * {@code group[/subgroup]/project} is URL-encoded to one segment ({@code group%2Fproject}).
     */
    static String projectRef(String project) {
        String p = project == null ? "" : project.trim();
        if (p.matches("\\d+")) return p;
        String[] segments = p.split("/", -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException("Parameter 'project' must be a project path 'group/project' or a numeric ID");
        }
        for (String segment : segments) {
            if (!PATH_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Parameter 'project' contains an invalid path segment: '" + segment + "'");
            }
        }
        return URLEncoder.encode(p, StandardCharsets.UTF_8);
    }

    /** GitHub-style state for listing → GitLab's {@code state} query value; {@code null} = all. */
    static String listState(String state) {
        return switch (state.trim().toLowerCase(Locale.ROOT)) {
            case "open", "opened" -> "opened";
            case "closed" -> "closed";
            case "all" -> null;
            default -> throw new IllegalArgumentException("Parameter 'state' must be open, closed or all");
        };
    }

    /** {@code open}/{@code closed} (or GitLab's own {@code reopen}/{@code close}) → {@code state_event}. */
    static String stateEvent(String state) {
        return switch (state.trim().toLowerCase(Locale.ROOT)) {
            case "open", "opened", "reopen" -> "reopen";
            case "closed", "close" -> "close";
            default -> throw new IllegalArgumentException("Parameter 'state' must be open or closed");
        };
    }

    static List<String> csv(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        if (value instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) result.add(o.toString().trim());
            return result;
        }
        for (String s : value.toString().split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** User IDs (GitLab assigns by numeric ID, not by name). */
    static List<Long> ids(Object value, String key) {
        List<Long> ids = new ArrayList<>();
        for (String s : csv(value)) {
            if (!s.matches("\\d+")) throw new IllegalArgumentException("Parameter '" + key + "' expects numeric user IDs");
            ids.add(Long.parseLong(s));
        }
        return ids;
    }

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    /** Missing or blank → {@code defaultValue}; a number is read like its text ({@link IntegrationParams}). */
    static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        return IntegrationParams.intParam(params, key, defaultValue);
    }

    /** Page size clamped to 1..{@link #MAX_PER_PAGE}. */
    static int perPage(Map<String, Object> params, int defaultValue) {
        return Math.max(1, Math.min(MAX_PER_PAGE, paramInt(params, "perPage", defaultValue)));
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) throw IntegrationParams.missing(key);
        return val.toString().trim();
    }

    /**
     * Issue IID (project-local number) — digits only, so it cannot alter the request path. A JSON
     * number is read like its text: {@code 8.0} fails instead of becoming 8 (TODO ac639b39).
     */
    static String requireIid(Map<String, Object> params, String key) {
        String s = requireParam(params, key);
        if (!s.matches("\\d+")) throw new IllegalArgumentException("Parameter '" + key + "' must be a positive number");
        return s;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
