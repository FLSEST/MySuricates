package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.automation.service.integration.OutboundTarget;
import com.automation.service.integration.providers.ical.CalendarParams;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP layer for CalDAV: the WebDAV methods (PROPFIND, REPORT, PUT, DELETE, GET) with Basic auth,
 * Depth / If-Match / If-None-Match headers, a response size cap and the error mapping the provider
 * relies on. Redirects are not followed — a redirect would carry the Basic-Auth header along — but
 * reported with their target so the user can enter the right URL. Messages never contain the password.
 * The server is an {@link OutboundTarget#ownService() own service}: local targets are refused only
 * with {@code private-network-guard-own-services} on.
 */
@Component
public class CalDavHttpClient {

    static final long MAX_RESPONSE_BYTES = 10L * 1024 * 1024;
    private static final String XML = "application/xml; charset=utf-8";

    private final OutboundClient httpClient;

    public CalDavHttpClient(OutboundHttp outbound) {
        this.httpClient = outbound.client(OutboundTarget.ownService(), Duration.ofSeconds(10));
    }

    /** Status, body and ETag of a successful call. */
    public record DavResult(int status, String body, String etag) {}

    public DavResult propfind(CalDavCredentials cred, URI uri, int depth, String body) {
        return send(request(cred, uri).header("Depth", String.valueOf(depth)).header("Content-Type", XML)
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(body)), "PROPFIND", uri);
    }

    public DavResult report(CalDavCredentials cred, URI uri, int depth, String body) {
        return send(request(cred, uri).header("Depth", String.valueOf(depth)).header("Content-Type", XML)
                .method("REPORT", HttpRequest.BodyPublishers.ofString(body)), "REPORT", uri);
    }

    public DavResult get(CalDavCredentials cred, URI uri) {
        return send(request(cred, uri).header("Accept", "text/calendar").GET(), "GET", uri);
    }

    /**
     * PUT of a calendar object. {@code ifMatch} = ETag for an optimistic update, {@code createOnly}
     * sends {@code If-None-Match: *} so an existing resource is never overwritten.
     */
    public DavResult put(CalDavCredentials cred, URI uri, String ics, String ifMatch, boolean createOnly) {
        HttpRequest.Builder b = request(cred, uri).header("Content-Type", "text/calendar; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(ics, StandardCharsets.UTF_8));
        if (ifMatch != null && !ifMatch.isBlank()) b.header("If-Match", ifMatch);
        if (createOnly) b.header("If-None-Match", "*");
        return send(b, "PUT", uri);
    }

    public DavResult delete(CalDavCredentials cred, URI uri, String ifMatch) {
        HttpRequest.Builder b = request(cred, uri).DELETE();
        if (ifMatch != null && !ifMatch.isBlank()) b.header("If-Match", ifMatch);
        return send(b, "DELETE", uri);
    }

    private HttpRequest.Builder request(CalDavCredentials cred, URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", cred.basicAuthHeader())
                .header("User-Agent", "MySuricates-CalDAV")
                .timeout(Duration.ofSeconds(20));
    }

    private DavResult send(HttpRequest.Builder builder, String method, URI uri) {
        HttpResponse<InputStream> response;
        String body;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            body = new String(CalendarParams.readBounded(response.body(), MAX_RESPONSE_BYTES, "CalDAV response"),
                    StandardCharsets.UTF_8);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CalDAV request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("CalDAV request failed (" + method + " " + uri.getHost() + "): "
                    + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : ""), e);
        }
        check(method, uri, response.statusCode(), body, response.headers().firstValue("Location").orElse(null));
        return new DavResult(response.statusCode(), body, response.headers().firstValue("ETag").orElse(null));
    }

    static void check(String method, URI uri, int status, String body, String location) {
        if (status < 300) return;
        if (status < 400) {
            throw new IllegalStateException("CalDAV server redirects " + method + " " + uri.getPath() + " to "
                    + (location != null ? location : "another address") + " — enter that URL instead");
        }
        String message = switch (status) {
            case 401 -> "CalDAV authentication failed — check user name and app password";
            case 403 -> "CalDAV access denied (403) for " + method + " " + uri.getPath();
            case 404 -> "CalDAV resource not found (404): " + uri.getPath();
            case 405 -> "CalDAV server does not allow " + method + " on " + uri.getPath() + " (405) — is this a CalDAV URL?";
            case 412 -> "CalDAV precondition failed (412) — the event was changed on the server (ETag) or already exists";
            default -> "CalDAV error " + status + " for " + method + " " + uri.getPath() + ": " + abbreviate(body);
        };
        throw new IllegalStateException(message);
    }

    private static String abbreviate(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
