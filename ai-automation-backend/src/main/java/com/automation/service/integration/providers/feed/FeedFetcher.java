package com.automation.service.integration.providers.feed;

import com.automation.modules.ModuleCatalog;
import com.automation.service.integration.OutboundTargetGuard;
import com.automation.service.integration.providers.IntegrationParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads a feed with hard limits: http/https only and a public target ({@link OutboundTargetGuard}),
 * both checked for every redirect hop, at most {@value #MAX_REDIRECTS} redirects followed by hand,
 * a response-size cap and timeouts.
 */
@Component
public class FeedFetcher {

    static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final String ACCEPT = "application/rss+xml, application/atom+xml, application/rdf+xml, "
            + "application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5";

    private final int maxBytes;
    private final OutboundTargetGuard guard;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Autowired
    public FeedFetcher(OutboundTargetGuard guard) {
        this(guard, DEFAULT_MAX_BYTES);
    }

    FeedFetcher(OutboundTargetGuard guard, int maxBytes) {
        this.guard = guard;
        this.maxBytes = maxBytes;
    }

    public byte[] fetch(String url) {
        URI uri = requireHttpUri(url);
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                guard.check(uri, ModuleCatalog.PRIVATE_NETWORK_GUARD);
                HttpResponse<InputStream> response = httpClient.send(request(uri), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (REDIRECTS.contains(status)) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IllegalStateException("Feed redirect without Location header"));
                        uri = requireHttpUri(uri.resolve(location.trim()).toString());
                        continue;
                    }
                    if (status >= 400) throw new IllegalStateException("Feed request failed: HTTP " + status + " for " + uri);
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declared > maxBytes) throw tooLarge();
                    return readLimited(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Feed request interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Feed request failed: " + e.getMessage(), e);
        }
        throw new IllegalStateException("Feed request failed: more than " + MAX_REDIRECTS + " redirects");
    }

    /** Absolute http(s) URL with a host; everything else (file:, ftp:, jar:, relative) is rejected. */
    static URI requireHttpUri(String url) {
        if (url == null || url.isBlank()) throw IntegrationParams.missing("url");
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid feed URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Feed URL must use http or https: " + url);
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("Feed URL without host: " + url);
        return uri;
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Accept", ACCEPT)
                .header("User-Agent", "MySuricates-FeedReader/1.0")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }

    private byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(maxBytes + 1);
        if (data.length > maxBytes) throw tooLarge();
        return data;
    }

    private IllegalStateException tooLarge() {
        return new IllegalStateException("Feed is larger than " + (maxBytes / 1024) + " KB");
    }
}
