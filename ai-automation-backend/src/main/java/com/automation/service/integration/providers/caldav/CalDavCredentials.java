package com.automation.service.integration.providers.caldav;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * CalDAV connection: base URL plus Basic-Auth user and app password, stored by the framework as
 * {@code "<url>|<user>:<password>"} (field 1 = URL, field 2 = {@code user:password}).
 *
 * <p>Every URL the provider requests is resolved through {@link #resolve} and must stay on the
 * origin (scheme, host, port) of the base URL — neither a server-supplied href nor an action
 * parameter can send the credentials to another host. {@link #toString()} never shows the password.
 */
public record CalDavCredentials(URI baseUrl, String username, String password) {

    public static CalDavCredentials parse(String combined) {
        if (combined == null || combined.isBlank()) {
            throw new IllegalStateException("CalDAV is not connected — enter the CalDAV URL and user:app-password");
        }
        int bar = combined.indexOf('|');
        if (bar < 0) throw new IllegalArgumentException("CalDAV credentials must be '<url>|<user>:<password>'");
        URI base = validateBaseUrl(combined.substring(0, bar).trim());
        String auth = combined.substring(bar + 1);
        int colon = auth.indexOf(':');
        if (colon <= 0 || colon == auth.length() - 1) {
            throw new IllegalArgumentException("Second field must be '<user>:<app-password>'");
        }
        return new CalDavCredentials(base, auth.substring(0, colon).trim(), auth.substring(colon + 1));
    }

    /** Only absolute http/https URLs without embedded user info; the path gets a trailing slash. */
    static URI validateBaseUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid CalDAV URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("CalDAV URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("CalDAV URL has no host");
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Put user and password into the second field, not into the URL");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!path.endsWith("/")) path += "/";
        return URI.create(scheme + "://" + uri.getRawAuthority() + path);
    }

    /** Resolves an href (absolute path or full URL) against the base; foreign origins are refused. */
    public URI resolve(String hrefOrUrl) {
        if (hrefOrUrl == null || hrefOrUrl.isBlank()) return baseUrl;
        URI target;
        try {
            target = baseUrl.resolve(new URI(hrefOrUrl.trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid CalDAV href: " + hrefOrUrl);
        }
        if (!sameOrigin(baseUrl, target)) {
            throw new IllegalArgumentException("CalDAV href points to another server than the connected one: " + target.getHost());
        }
        return target;
    }

    /** Like {@link #resolve} for a collection: guarantees the trailing slash. */
    public URI resolveCollection(String hrefOrUrl) {
        URI uri = resolve(hrefOrUrl);
        String s = uri.toString();
        return s.endsWith("/") ? uri : URI.create(s + "/");
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(Objects.toString(b.getScheme(), ""))
                && a.getHost().equalsIgnoreCase(Objects.toString(b.getHost(), ""))
                && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    String basicAuthHeader() {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "CalDavCredentials[" + baseUrl + ", user=" + username + ", password=***]";
    }
}
