package com.automation.service.integration.providers.httpapi;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The service one {@code http-api} connection talks to: base URL plus an optional auth header.
 * Stored as the connection's two credential fields (field 1 = base URL, field 2 = {@code Name: Value});
 * the framework keeps them combined as {@code <base URL>|<Name: Value>}.
 *
 * <p>Validation is strict because everything a block asks for is resolved against this base:
 * only {@code http}/{@code https}, a host, no userinfo, no query, no fragment, no whitespace or
 * control characters, and no {@code |} (the credential separator).
 */
record HttpApiTarget(URI base, String authName, String authValue) {

    /** RFC 9110 token — a header name. */
    static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    HttpApiTarget {
        if (authName != null && authName.isEmpty()) authName = null;
    }

    /** Parses and checks the two credential fields; {@link IllegalArgumentException} with a user-facing reason. */
    static HttpApiTarget parse(String baseUrl, String authHeader) {
        return new HttpApiTarget(parseBase(baseUrl), headerName(authHeader), headerValue(authHeader));
    }

    /** The combined credential {@code <base URL>|<Name: Value>} as the framework stores it. */
    static HttpApiTarget fromCredential(String combined) {
        if (combined == null || combined.isBlank()) {
            throw new IllegalStateException("No http-api connection configured — enter a base URL on the Integrations page");
        }
        int bar = combined.indexOf('|');
        String base = bar < 0 ? combined : combined.substring(0, bar);
        String header = bar < 0 ? "" : combined.substring(bar + 1);
        return parse(base, header);
    }

    boolean hasAuth() {
        return authName != null;
    }

    /** Base path without trailing slash; empty for the root. */
    String basePath() {
        String path = base.getRawPath() == null ? "" : base.getRawPath();
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** {@code host[:port]/base-path} — what the UI shows as account, never the auth header. */
    String label() {
        return base.getRawAuthority() + basePath();
    }

    /** Replaces the auth value wherever it appears (a service echoing request headers must not leak it). */
    String redact(String text) {
        if (text == null || authValue == null || authValue.length() < 4) return text;
        return text.replace(authValue, "***");
    }

    private static URI parseBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Base URL is required (e.g. https://api.example.com/v1)");
        if (value.indexOf('|') >= 0 || hasWhitespaceOrControl(value)) {
            throw new IllegalArgumentException("Base URL must not contain spaces, control characters or '|'");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Base URL is not a valid URL: " + e.getReason());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Base URL must start with http:// or https://");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Base URL must not contain user:password@ — use the auth header field");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("Base URL needs a host name");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Base URL must not contain '?' or '#' — pass query parameters per request");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty() && !uri.normalize().getRawPath().equals(path)) {
            throw new IllegalArgumentException("Base URL path must not contain '.' or '..' segments");
        }
        return URI.create(scheme + "://" + uri.getRawAuthority() + (path.isEmpty() ? "/" : path));
    }

    private static String headerName(String header) {
        if (header == null || header.isBlank()) return null;
        int colon = header.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Auth header must look like 'Name: Value', e.g. 'Authorization: Bearer <token>'");
        String name = header.substring(0, colon).trim();
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Auth header name '" + abbreviate(name) + "' is not a valid HTTP header name");
        }
        if (HttpApiHeaders.isForbidden(name)) {
            throw new IllegalArgumentException("Auth header '" + name + "' is not allowed");
        }
        return name;
    }

    private static String headerValue(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.substring(header.indexOf(':') + 1).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Auth header has no value");
        if (HttpApiHeaders.hasIllegalValueChar(value)) {
            throw new IllegalArgumentException("Auth header value must not contain line breaks or control characters");
        }
        return value;
    }

    private static boolean hasWhitespaceOrControl(String s) {
        return s.chars().anyMatch(c -> c <= 0x20 || c == 0x7f);
    }

    private static String abbreviate(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** Never print the auth value. */
    @Override
    public String toString() {
        return "HttpApiTarget[" + label() + (hasAuth() ? ", auth header " + authName : "") + "]";
    }
}
