package com.automation.service.integration.providers.icalfeed;

import com.automation.service.integration.providers.ical.CalendarParams;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Downloads a public iCalendar feed: http/https only ({@code webcal://} is read as https), 10 s
 * connect / 20 s total timeout, at most {@link #MAX_BYTES} and only a body that is an iCalendar.
 * Feed URLs often carry a secret token in path or query (private Google/Outlook links), so errors
 * and logs name the host only, never the full URL.
 */
@Component
public class ICalFeedFetcher {

    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String fetch(URI url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(url)
                .header("Accept", "text/calendar, */*;q=0.5")
                .header("User-Agent", "MySuricates-ICalFeed")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        String body;
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                response.body().close();
                throw new IllegalStateException("iCal feed at " + url.getHost() + " answered HTTP " + response.statusCode());
            }
            long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (announced > MAX_BYTES) {
                response.body().close();
                throw new IllegalStateException("iCal feed exceeds the size limit of " + (MAX_BYTES / (1024 * 1024)) + " MB");
            }
            body = new String(CalendarParams.readBounded(response.body(), MAX_BYTES, "iCal feed"), StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("iCal feed request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("iCal feed at " + url.getHost() + " could not be loaded: "
                    + e.getClass().getSimpleName(), e);
        }
        if (!body.contains("BEGIN:VCALENDAR")) {
            throw new IllegalStateException("Response from " + url.getHost() + " is not an iCalendar feed");
        }
        return body;
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
