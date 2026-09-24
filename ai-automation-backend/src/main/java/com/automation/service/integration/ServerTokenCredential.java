package com.automation.service.integration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Credential of a self-hostable service that is reached by URL and token (Gitea/Forgejo, GitLab).
 *
 * <p>The connect flow stores the two UI fields as {@code "<server url>|<token>"}. {@link #parse}
 * splits that value, validates the URL (http/https only, a host, no user info, no query or fragment)
 * and drops trailing slashes, so callers can append {@code /api/...} directly.
 *
 * <p>Error messages never contain the token, and {@link #toString()} masks it — the record may end
 * up in a log line or an exception message by accident.
 */
public record ServerTokenCredential(String baseUrl, String token) {

    /**
     * @param combined   the stored credential ({@code "url|token"}; a value without {@code |} is a bare token)
     * @param defaultUrl used when the URL part is empty, e.g. {@code https://gitlab.com}; {@code null} = URL required
     */
    public static ServerTokenCredential parse(String combined, String defaultUrl) {
        if (combined == null || combined.isBlank()) {
            throw new IllegalArgumentException("Credential missing: expected server URL and access token");
        }
        int idx = combined.indexOf('|');
        String urlPart = idx >= 0 ? combined.substring(0, idx).trim() : "";
        String token = (idx >= 0 ? combined.substring(idx + 1) : combined).trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Access token missing");
        }
        if (urlPart.isEmpty()) {
            if (defaultUrl == null) throw new IllegalArgumentException("Server URL missing");
            urlPart = defaultUrl;
        }
        return new ServerTokenCredential(normalizeServerUrl(urlPart), token);
    }

    /** Validates a server URL and returns it without trailing slashes ({@code https://host[:port][/path]}). */
    public static String normalizeServerUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Server URL missing");
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Server URL is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Server URL must start with http:// or https://");
        }
        if (uri.getRawUserInfo() != null) {
            // Never echo the URL here: the user info may be a password.
            throw new IllegalArgumentException("Server URL must not contain user credentials");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Server URL has no host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Server URL must not contain a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return scheme + "://" + uri.getRawAuthority() + path;
    }

    /** Host part of the base URL, for display. */
    public String host() {
        return URI.create(baseUrl).getHost();
    }

    @Override
    public String toString() {
        return "ServerTokenCredential[baseUrl=" + baseUrl + ", token=***]";
    }
}
