package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.providers.IntegrationParams;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Parameter parsing shared by the Todoist actions — pure functions, no I/O. */
final class TodoistParams {

    /** API v1 ids are opaque strings (e.g. {@code 6XGgmFVcrG5RRjVr}); anything else never reaches a URL path. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    static final int MAX_LIMIT = 200;

    private TodoistParams() {}

    /** Extracts the API token from the framework's "label|token" credential format. */
    static String extractToken(String combined) {
        if (combined == null) return null;
        int idx = combined.indexOf('|');
        return idx >= 0 ? combined.substring(idx + 1) : combined;
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new IllegalArgumentException("Required parameter missing: " + key);
        }
        return val.toString();
    }

    static String optionalString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val == null || val.toString().isBlank() ? null : val.toString();
    }

    /** Validates an id before it is used in a URL path or body. */
    static String id(String value, String name) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Parameter '" + name + "' is not a valid Todoist id");
        }
        return value;
    }

    static String requireId(Map<String, Object> params, String key) {
        return id(requireParam(params, key), key);
    }

    /** Page size, 1..200 as documented for the paginated endpoints (default 50); read like its text. */
    static int limit(Map<String, Object> params) {
        Integer limit = IntegrationParams.optionalInt(params.get("limit"), "limit");
        if (limit == null) return 50;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Parameter 'limit' must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    /** Todoist priority: 1 (normal) … 4 (urgent, shown as p1 in the apps); read like its text. */
    static Integer priority(Map<String, Object> params) {
        Integer p;
        try {
            p = IntegrationParams.parseInt(params.get("priority"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Parameter 'priority' must be a number between 1 and 4");
        }
        if (p == null) return null;
        if (p < 1 || p > 4) {
            throw new IllegalArgumentException("Parameter 'priority' must be between 1 (normal) and 4 (urgent)");
        }
        return p;
    }

    /** Label names as a list — accepts a JSON array or a comma-separated string. */
    static List<String> labels(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        Collection<?> raw = value instanceof Collection<?> c ? c : List.of((Object[]) value.toString().split(","));
        for (Object o : raw) {
            String trimmed = o == null ? "" : o.toString().trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
