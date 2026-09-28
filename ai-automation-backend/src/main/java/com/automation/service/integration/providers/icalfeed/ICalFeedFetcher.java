package com.automation.service.integration.providers.icalfeed;

import com.automation.modules.ModuleCatalog;
import com.automation.service.integration.OutboundTargetGuard;
import com.automation.service.integration.providers.ical.CalendarParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads a public iCalendar feed: http/https only ({@code webcal://} is read as https), a public
 * target ({@link OutboundTargetGuard}), 10 s connect / 20 s total timeout, at most {@link #MAX_BYTES}
 * and only a body that is an iCalendar. Redirects are followed by hand (at most
 * {@value #MAX_REDIRECTS}, never from https to http) so that every hop passes the same checks.
 * Feed URLs often carry a secret token in path or query (private Google/Outlook links), so errors
 * and logs name the host only, never the full URL.
 */
@Component
public class ICalFeedFetcher {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    private final OutboundTargetGuard guard;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ICalFeedFetcher(OutboundTargetGuard guard) {
        this.guard = guard;
    }

    public String fetch(URI url) {
        URI uri = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            guard.check(uri, ModuleCatalog.PRIVATE_NETWORK_GUARD);
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (REDIRECTS.contains(status)) {
                close(response);
                uri = redirectTarget(uri, response.headers().firstValue("Location").orElse(null));
                continue;
            }
            return read(uri, response);
        }
        throw new IllegalStateException("iCal feed at " + url.getHost() + " redirected more than " + MAX_REDIRECTS + " times");
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "text/calendar, */*;q=0.5")
                .header("User-Agent", "MySuricates-ICalFeed")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("iCal feed request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("iCal feed at " + uri.getHost() + " could not be loaded: "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /** Next hop: http/https with a host, no user info, no downgrade from https to http. */
    private static URI redirectTarget(URI from, String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("iCal feed at " + from.getHost() + " redirected without a Location header");
        }
        URI next;
        try {
            next = validate(from.resolve(location.trim()).toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("iCal feed at " + from.getHost() + " redirected to an invalid URL: " + e.getMessage());
        }
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
            throw new IllegalStateException("iCal feed at " + from.getHost() + " redirected from https to http");
        }
        return next;
    }

    private static String read(URI uri, HttpResponse<InputStream> response) {
        String body;
        try (InputStream in = response.body()) {
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("iCal feed at " + uri.getHost() + " answered HTTP " + response.statusCode());
            }
            long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (announced > MAX_BYTES) {
                throw new IllegalStateException("iCal feed exceeds the size limit of " + (MAX_BYTES / (1024 * 1024)) + " MB");
            }
            body = new String(CalendarParams.readBounded(in, MAX_BYTES, "iCal feed"), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("iCal feed at " + uri.getHost() + " could not be loaded: "
                    + e.getClass().getSimpleName(), e);
        }
        if (!body.contains("BEGIN:VCALENDAR")) {
            throw new IllegalStateException("Response from " + uri.getHost() + " is not an iCalendar feed");
        }
        return body;
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // the next hop does not depend on this body
        }
    }

    /** Validates a feed URL: {@code webcal://} → https, otherwise only http/https with a host and no user info. */
    public static URI validate(String url) {
        String u = url == null ? "" : url.trim();
        if (u.regionMatches(true, 0, "webcal://", 0, 9)) u = "https://" + u.substring(9);
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid feed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Feed URL must start with http://, https:// or webcal://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("Feed URL has no host");
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("Feed URL must not contain user credentials");
        return uri;
    }
}
