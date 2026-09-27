package com.automation.service.integration.providers.httpapi;

import com.automation.service.integration.OutboundTargetGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends one {@link HttpApiRequest} and turns the answer into the action result. The target must be
 * a public address ({@link OutboundTargetGuard}, checked on every request). Redirects are
 * never followed (a 3xx comes back with its {@code location}), the response is read up to
 * {@link #MAX_RESPONSE_BYTES}, and the connection's auth value is scrubbed from everything that
 * goes back — a service that echoes request headers must not leak it into a step output.
 * Logs name method, host and path only, never headers.
 */
@Component
@Slf4j
class HttpApiClient {

    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int MAX_TEXT_CHARS = 100_000;
    private static final int ERROR_SNIPPET_CHARS = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OutboundTargetGuard guard;

    @Autowired
    HttpApiClient(ObjectMapper objectMapper, OutboundTargetGuard guard) {
        this(objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), guard);
    }

    HttpApiClient(ObjectMapper objectMapper, HttpClient httpClient, OutboundTargetGuard guard) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.guard = guard;
    }

    Map<String, Object> send(HttpApiTarget target, HttpApiRequest request) {
        guard.check(request.uri());
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        if (target.hasAuth()) builder.header(target.authName(), target.authValue());
        builder.method(request.method(), request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));

        String where = request.method() + " " + request.uri().getRawAuthority() + request.uri().getRawPath();
        log.info("http-api request: {}", where);
        HttpResponse<InputStream> response;
        byte[] bytes;
        boolean truncated;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                byte[] read = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                truncated = read.length > MAX_RESPONSE_BYTES;
                bytes = truncated ? Arrays.copyOf(read, MAX_RESPONSE_BYTES) : read;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http-api request interrupted: " + where);
        } catch (IOException e) {
            throw new IllegalStateException("http-api request failed (" + where + "): " + target.redact(e.getMessage()));
        }

        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        String text = target.redact(new String(bytes, StandardCharsets.UTF_8));
        log.info("http-api response: {} → {}", where, status);

        if (request.failOnError() && status >= 400) {
            String snippet = text.length() > ERROR_SNIPPET_CHARS ? text.substring(0, ERROR_SNIPPET_CHARS) + "…" : text;
            throw new IllegalStateException("HTTP " + status + " from " + where + (snippet.isBlank() ? "" : ": " + snippet));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("ok", status >= 200 && status < 300);
        result.put("content_type", contentType);
        if (status >= 300 && status < 400) {
            response.headers().firstValue("Location").ifPresent(l -> result.put("location", target.redact(l)));
            result.put("note", "redirects are not followed");
        }
        result.put("body", body(text, contentType, truncated));
        result.put("truncated", truncated || text.length() > MAX_TEXT_CHARS);
        return result;
    }

    /** JSON parsed when the answer says so and arrived complete, else text (cut at {@link #MAX_TEXT_CHARS}). */
    private Object body(String text, String contentType, boolean truncated) {
        if (text.isEmpty()) return null;
        if (!truncated && isJson(contentType)) {
            try {
                return objectMapper.readTree(text);
            } catch (IOException e) {
                log.debug("http-api: body announced as JSON but did not parse — returned as text");
            }
        }
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("application/json") || type.contains("+json");
    }
}
