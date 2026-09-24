package com.automation.service.integration.providers.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Thin HTTP layer for the Slack Web API ({@code https://slack.com/api/<method>}): bearer auth,
 * JSON bodies for writes, query strings for reads. Slack answers most failures with HTTP 200 and
 * {@code {"ok":false,"error":"…"}} — this client turns those into exceptions, next to HTTP 429
 * ({@code Retry-After}) and other non-2xx statuses. Only Slack's error code reaches the message.
 */
@Component
public class SlackHttpClient {

    static final String DEFAULT_BASE_URL = "https://slack.com/api";
    private static final Set<String> AUTH_ERRORS = Set.of(
            "not_authed", "invalid_auth", "account_inactive", "token_revoked", "token_expired", "no_permission");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** Parsed {@code ok:true} answer plus the {@code x-oauth-scopes} header Slack sends along. */
    public record SlackResponse(Map<String, Object> body, String scopesHeader) {}

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    public SlackHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_BASE_URL);
    }

    /** Test seam: points the client at a local stub server. */
    SlackHttpClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /** Read method (e.g. {@code conversations.list}) with URL-encoded query arguments. */
    public SlackResponse get(String method, Map<String, String> query, String token) {
        StringJoiner qs = new StringJoiner("&", "?", "").setEmptyValue("");
        query.forEach((k, v) -> qs.add(encode(k) + "=" + encode(v)));
        return send(request(method + qs, token).GET().build(), token);
    }

    /** Write method (e.g. {@code chat.postMessage}) with a JSON body. */
    public SlackResponse post(String method, Map<String, Object> body, String token) {
        return send(request(method, token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), token);
    }

    private HttpRequest.Builder request(String methodAndQuery, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/" + methodAndQuery))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15));
    }

    private SlackResponse send(HttpRequest request, String token) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Slack API request interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("Slack API request failed: " + scrub(e.getMessage(), token));
        }
        int status = response.statusCode();
        if (status == 429) {
            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            throw new IllegalStateException("Slack rate limit reached (HTTP 429)"
                    + (retryAfter.isBlank() ? "" : " — retry after " + retryAfter + " s"));
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Slack API error " + status);
        }
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(response.body(), MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Slack API returned no valid JSON (HTTP " + status + ")");
        }
        if (body == null || !Boolean.TRUE.equals(body.get("ok"))) {
            throw slackError(body);
        }
        return new SlackResponse(body, response.headers().firstValue("x-oauth-scopes").orElse(null));
    }

    private static IllegalStateException slackError(Map<String, Object> body) {
        Object code = body == null ? null : body.get("error");
        String error = code == null ? "unknown_error" : code.toString();
        if (AUTH_ERRORS.contains(error)) {
            return new IllegalStateException("Slack authentication failed (" + error + ") — check the bot token");
        }
        if ("missing_scope".equals(error) && body.get("needed") != null) {
            return new IllegalStateException("Slack API error: missing_scope (needed: " + body.get("needed") + ")");
        }
        return new IllegalStateException("Slack API error: " + error);
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Slack request body");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String scrub(String message, String token) {
        if (message == null) return "connection error";
        return token == null || token.isEmpty() ? message : message.replace(token, "***");
    }
}
