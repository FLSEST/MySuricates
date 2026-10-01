package com.automation.service.integration.providers.ntfy;

import com.automation.service.integration.providers.IntegrationParams;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Connection and parameter parsing for ntfy — pure functions, no I/O. */
final class NtfyParams {

    static final String DEFAULT_SERVER = "https://ntfy.sh";

    /** ntfy's own topic rule: letters, digits, '-' and '_', at most 64 characters. */
    private static final Pattern TOPIC = Pattern.compile("^[-_A-Za-z0-9]{1,64}$");

    private static final Map<String, Integer> PRIORITY_NAMES = Map.of(
            "min", 1, "low", 2, "default", 3, "high", 4, "max", 5, "urgent", 5);

    private NtfyParams() {}

    /** Server base URL (never blank, no trailing slash) and the optional access token. */
    record Connection(String baseUrl, String token) {
        boolean hasToken() {
            return token != null;
        }
    }

    /**
     * Parses the framework's {@code "serverUrl|token"} credential. Both halves may be empty:
     * no server ⇒ {@link #DEFAULT_SERVER}, no token ⇒ anonymous. {@code null} (no connection) is anonymous ntfy.sh.
     */
    static Connection parseConnection(String combined) {
        if (combined == null) return new Connection(DEFAULT_SERVER, null);
        int idx = combined.indexOf('|');
        String server = idx >= 0 ? combined.substring(0, idx) : combined;
        String token = idx >= 0 ? combined.substring(idx + 1).trim() : "";
        return new Connection(normalizeServer(server), token.isEmpty() ? null : token);
    }

    /** Blank ⇒ ntfy.sh; otherwise an absolute http(s) URL without query/fragment, trailing slash removed. */
    static String normalizeServer(String server) {
        if (server == null || server.isBlank()) return DEFAULT_SERVER;
        String trimmed = server.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid ntfy server URL: " + trimmed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("ntfy server URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid ntfy server URL: " + trimmed);
        }
        return trimmed;
    }

    static String requireTopic(Map<String, Object> params) {
        Object value = params.get("topic");
        String topic = value == null ? "" : value.toString().trim();
        if (!TOPIC.matcher(topic).matches()) {
            throw new IllegalArgumentException(
                    "Parameter 'topic' must be 1-64 characters of letters, digits, '-' or '_'");
        }
        return topic;
    }

    /** 1–5 or min/low/default/high/max (urgent = max); {@code null} when not given. */
    static Integer priority(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return checkPriority(n.doubleValue(), value);
        String s = value.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        Integer named = PRIORITY_NAMES.get(s);
        if (named != null) return named;
        try {
            return checkPriority(Double.parseDouble(s), value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(invalidPriority(value));
        }
    }

    private static int checkPriority(double p, Object original) {
        if (p != Math.rint(p) || p < 1 || p > 5) throw new IllegalArgumentException(invalidPriority(original));
        return (int) p;
    }

    private static String invalidPriority(Object value) {
        return "Parameter 'priority' must be 1-5 or min/low/default/high/max, got: " + value;
    }

    /** Comma-separated string or list → trimmed, non-empty tags. */
    static List<String> tags(Object value) {
        List<String> result = new ArrayList<>();
        if (value == null) return result;
        Iterable<?> parts = value instanceof List<?> list ? list : List.of(value.toString().split(","));
        for (Object part : parts) {
            String tag = part == null ? "" : part.toString().trim();
            if (!tag.isEmpty()) result.add(tag);
        }
        return result;
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }

    /** JSON body for ntfy's JSON publishing (POST to the server root). */
    static Map<String, Object> publishBody(Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", requireTopic(params));
        Object message = params.get("message");
        if (message == null || message.toString().isBlank()) {
            throw IntegrationParams.missing("message");
        }
        body.put("message", message.toString());
        putText(body, "title", params.get("title"));
        Integer priority = priority(params.get("priority"));
        if (priority != null) body.put("priority", priority);
        List<String> tags = tags(params.get("tags"));
        if (!tags.isEmpty()) body.put("tags", tags);
        putText(body, "click", params.get("click"));
        if (bool(params.get("markdown"))) body.put("markdown", true);
        return body;
    }

    private static void putText(Map<String, Object> body, String key, Object value) {
        if (value != null && !value.toString().isBlank()) body.put(key, value.toString().trim());
    }
}
