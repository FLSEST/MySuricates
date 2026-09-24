package com.automation.service.integration.providers.gitea;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Parameter parsing shared by the Gitea actions — pure functions, no I/O. */
final class GiteaParams {

    /** Gitea caps page sizes at its {@code MAX_RESPONSE_ITEMS} (default 50). */
    static final int MAX_LIMIT = 50;

    private GiteaParams() {}

    static String[] splitRepo(String repo) {
        String[] parts = repo == null ? new String[0] : repo.trim().split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Parameter 'repo' must be in the form 'owner/repo'");
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
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

    static String paramString(Map<String, Object> params, String key, String defaultValue) {
        Object val = params.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    static int paramInt(Map<String, Object> params, String key, int defaultValue) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a number");
            }
        }
        return defaultValue;
    }

    /** Page size clamped to 1..{@link #MAX_LIMIT}. */
    static int limit(Map<String, Object> params, int defaultValue) {
        return Math.max(1, Math.min(MAX_LIMIT, paramInt(params, "perPage", defaultValue)));
    }

    static String requireParam(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null || val.toString().isBlank()) throw new IllegalArgumentException("Required parameter missing: " + key);
        return val.toString().trim();
    }

    /** Issue number ("index" in Gitea terms) — digits only, so it cannot alter the request path. */
    static String requireNumber(Map<String, Object> params, String key) {
        Object val = params.get(key);
        String s = val instanceof Number n ? String.valueOf(n.longValue()) : requireParam(params, key);
        if (!s.matches("\\d+")) throw new IllegalArgumentException("Parameter '" + key + "' must be a positive number");
        return s;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
