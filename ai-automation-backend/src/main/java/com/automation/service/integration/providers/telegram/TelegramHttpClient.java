package com.automation.service.integration.providers.telegram;

import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Thin HTTP layer for the Telegram Bot API ({@code https://api.telegram.org/bot<token>/<method>}).
 *
 * <p>The bot token is part of the URL path, so it must never reach an exception message, a log line
 * or an exception cause: the token is format-checked before any URI is built (a malformed token
 * would otherwise be echoed by {@code URI.create}), transport exceptions are rethrown without their
 * cause and with the token scrubbed from the message, and errors carry only the Bot API's
 * {@code error_code}/{@code description}.
 */
@Component
public class TelegramHttpClient {

    static final String DEFAULT_BASE_URL = "https://api.telegram.org";
    /** "123456789:AAH…" — bot id, colon, secret part. */
    private static final Pattern TOKEN = Pattern.compile("\\d{1,20}:[A-Za-z0-9_-]{20,120}");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final OutboundClient httpClient = OutboundHttp.fixedHost(Duration.ofSeconds(10));

    @Autowired
    public TelegramHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_BASE_URL);
    }

    /** Test seam: points the client at a local stub server. */
    TelegramHttpClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /** Calls a Bot API method with a JSON body and returns its {@code result}. */
    public Object call(String token, String method, Map<String, Object> body) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Telegram bot token has an invalid format (expected <bot-id>:<secret> from @BotFather)");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/bot" + token + "/" + method))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram API request interrupted");
        } catch (Exception e) {
            String detail = scrub(e.getMessage(), token);
            throw new IllegalStateException("Telegram API request failed: "
                    + e.getClass().getSimpleName() + (detail.isEmpty() ? "" : ": " + detail));
        }
        return result(response, token);
    }

    private Object result(HttpResponse<String> response, String token) {
        int status = response.statusCode();
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(response.body(), MAP);
        } catch (Exception e) {
            throw new IllegalStateException(status >= 400
                    ? "Telegram API error " + status
                    : "Telegram API returned no valid JSON (HTTP " + status + ")");
        }
        if (body != null && Boolean.TRUE.equals(body.get("ok")) && status < 400) {
            return body.get("result");
        }
        int code = body != null && body.get("error_code") instanceof Number n ? n.intValue() : status;
        String description = body != null && body.get("description") != null
                ? scrub(body.get("description").toString(), token) : "";
        if (code == 401) {
            throw new IllegalStateException("Telegram authentication failed — check the bot token");
        }
        if (code == 429) {
            Object retry = body != null && body.get("parameters") instanceof Map<?, ?> p ? p.get("retry_after") : null;
            throw new IllegalStateException("Telegram rate limit reached (HTTP 429)"
                    + (retry == null ? "" : " — retry after " + retry + " s"));
        }
        throw new IllegalStateException("Telegram API error " + code + (description.isBlank() ? "" : ": " + description));
    }

    /** The message with the token replaced, or "" when there is none. */
    static String scrub(String message, String token) {
        if (message == null || message.isBlank()) return "";
        return message.replace(token, "***");
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Telegram request body");
        }
    }
}
