package com.automation.service.integration.providers.httpapi;

import com.automation.service.integration.providers.IntegrationParams;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One validated request of the {@code request} action, built from the block params against a
 * {@link HttpApiTarget}. Every check that keeps a block inside its connection lives here:
 *
 * <ul>
 *   <li><b>path</b> is relative to the base URL and cannot leave it — no scheme, no {@code //host},
 *       no backslash, no {@code ?}/{@code #}, and no segment that is {@code .} or {@code ..} or
 *       contains a slash once percent-decoded. The final URI is re-checked against scheme, host,
 *       port and base-path prefix.</li>
 *   <li><b>headers</b> must be valid names, carry no CR/LF/control characters, and may not set the
 *       connection's auth header or a hop-by-hop/framing header.</li>
 *   <li><b>body</b> only for POST/PUT/PATCH/DELETE, at most {@link #MAX_BODY_BYTES}.</li>
 * </ul>
 */
record HttpApiRequest(String method, URI uri, Map<String, String> headers, byte[] body, Duration timeout,
                      boolean failOnError) {

    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    static final int MAX_TIMEOUT_SECONDS = 120;

    private static final Pattern SCHEME_PREFIX = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

    static HttpApiRequest from(HttpApiTarget target, Map<String, Object> params, ObjectMapper json) {
        Map<String, Object> p = params == null ? Map.of() : params;
        String method = method(p.get("method"));
        URI uri = uri(target, string(p.get("path"), "path"), p.get("query"));
        Map<String, String> headers = headers(target, p.get("headers"));
        byte[] body = body(method, p.get("body"), headers, json);
        return new HttpApiRequest(method, uri, headers, body, timeout(p.get("timeout_seconds")),
                !Boolean.FALSE.equals(bool(p.get("fail_on_error"), "fail_on_error")));
    }

    private static String method(Object raw) {
        String method = raw == null ? "GET" : string(raw, "method").trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("method must be one of GET, POST, PUT, PATCH, DELETE");
        }
        return method;
    }

    // ── path + query ─────────────────────────────────────────────────────────

    static URI uri(HttpApiTarget target, String rawPath, Object query) {
        String path = checkedRelativePath(rawPath == null ? "" : rawPath);
        String basePath = target.basePath();
        String full = path.isEmpty() ? (basePath.isEmpty() ? "/" : basePath) : basePath + "/" + path;
        String queryString = query(query);
        URI uri;
        try {
            uri = new URI(target.base().getScheme() + "://" + target.base().getRawAuthority() + full
                    + (queryString.isEmpty() ? "" : "?" + queryString));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("path is not a valid URL path: " + e.getReason());
        }
        boolean sameOrigin = Objects.equals(uri.getScheme(), target.base().getScheme())
                && Objects.equals(uri.getRawAuthority(), target.base().getRawAuthority());
        String resolved = uri.normalize().getRawPath();
        boolean insideBase = basePath.isEmpty() || resolved.equals(basePath) || resolved.startsWith(basePath + "/");
        if (!sameOrigin || !insideBase || !resolved.equals(uri.getRawPath())) {
            throw new IllegalArgumentException("path must stay below the connection's base URL");
        }
        return uri;
    }

    private static String checkedRelativePath(String raw) {
        if (raw.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("path must not contain spaces or control characters (percent-encode them)");
        }
        if (raw.startsWith("//") || SCHEME_PREFIX.matcher(raw).find()) {
            throw new IllegalArgumentException("path must be relative to the base URL — no absolute URL, scheme or //host");
        }
        if (raw.indexOf('\\') >= 0 || raw.indexOf('?') >= 0 || raw.indexOf('#') >= 0) {
            throw new IllegalArgumentException("path must not contain '\\', '?' or '#' — pass query parameters via 'query'");
        }
        String path = raw.startsWith("/") ? raw.substring(1) : raw;
        for (String segment : path.split("/", -1)) {
            String decoded = percentDecode(segment);
            if (decoded.equals(".") || decoded.equals("..") || decoded.indexOf('/') >= 0 || decoded.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("path must not contain '.' or '..' segments or encoded slashes");
            }
        }
        return path;
    }

    private static String percentDecode(String segment) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%') {
                if (i + 2 >= segment.length() || Character.digit(segment.charAt(i + 1), 16) < 0
                        || Character.digit(segment.charAt(i + 2), 16) < 0) {
                    throw new IllegalArgumentException("path contains an invalid percent-escape");
                }
                out.write(Integer.parseInt(segment.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String query(Object raw) {
        if (raw == null) return "";
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("query must be an object of name → value");
        List<String> pairs = new ArrayList<>();
        map.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.isBlank()) throw new IllegalArgumentException("query parameter names must not be blank");
            List<?> values = v instanceof List<?> list ? list : java.util.Collections.singletonList(v);
            for (Object value : values) {
                if (value == null) continue;
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    throw new IllegalArgumentException("query parameter '" + key + "' must be a string, number or boolean");
                }
                pairs.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        return String.join("&", pairs);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ── headers + body ───────────────────────────────────────────────────────

    private static Map<String, String> headers(HttpApiTarget target, Object raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null) return headers;
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("headers must be an object of name → value");
        map.forEach((k, v) -> {
            String name = String.valueOf(k).trim();
            if (!HttpApiTarget.HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + abbreviate(name) + "' is not a valid HTTP header name");
            }
            if (HttpApiHeaders.isForbidden(name)) throw new IllegalArgumentException("header '" + name + "' is not allowed");
            if (target.hasAuth() && name.equalsIgnoreCase(target.authName())) {
                throw new IllegalArgumentException("header '" + name + "' is the connection's auth header and cannot be overridden");
            }
            if (v == null || v instanceof Map<?, ?> || v instanceof List<?>) {
                throw new IllegalArgumentException("header '" + name + "' needs a string value");
            }
            String value = String.valueOf(v);
            if (HttpApiHeaders.hasIllegalValueChar(value)) {
                throw new IllegalArgumentException("header '" + name + "' must not contain line breaks or control characters");
            }
            headers.put(name, value);
        });
        return headers;
    }

    private static byte[] body(String method, Object raw, Map<String, String> headers, ObjectMapper json) {
        if (raw == null) return null;
        if (method.equals("GET")) throw new IllegalArgumentException("a GET request cannot have a body");
        boolean text = raw instanceof String;
        byte[] bytes;
        try {
            bytes = text ? ((String) raw).getBytes(StandardCharsets.UTF_8) : json.writeValueAsBytes(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("body cannot be written as JSON: " + e.getOriginalMessage());
        }
        if (bytes.length > MAX_BODY_BYTES) throw new IllegalArgumentException("body is larger than 1 MiB");
        if (headers.keySet().stream().noneMatch(h -> h.equalsIgnoreCase("Content-Type"))) {
            headers.put("Content-Type", text ? "text/plain; charset=utf-8" : "application/json");
        }
        return bytes;
    }

    /** Read like its text ({@link IntegrationParams}): 1.5 fails instead of becoming 1; blank means the default. */
    private static Duration timeout(Object raw) {
        Integer seconds;
        try {
            seconds = IntegrationParams.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timeout_seconds must be a number");
        }
        if (seconds == null) return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeout_seconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        return Duration.ofSeconds(seconds);
    }

    private static String string(Object raw, String name) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> || raw instanceof List<?>) throw new IllegalArgumentException(name + " must be a string");
        return String.valueOf(raw);
    }

    private static Boolean bool(Object raw, String name) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("false")) return Boolean.valueOf(s);
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
