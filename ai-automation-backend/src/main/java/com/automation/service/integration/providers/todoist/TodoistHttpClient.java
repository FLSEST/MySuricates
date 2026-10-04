package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer for the Todoist API v1 ({@code https://api.todoist.com/api/v1}): bearer auth,
 * JSON (de)serialisation and the error mapping. Since API v1 every error is JSON
 * ({@code error}, {@code error_code}, {@code error_tag}, {@code http_code}, {@code error_extra.retry_after});
 * only {@code error} and {@code error_tag} reach the message, never the raw body.
 */
@Component
public class TodoistHttpClient {

    static final String DEFAULT_BASE_URL = "https://api.todoist.com/api/v1";

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final OutboundClient httpClient = OutboundHttp.fixedHost(Duration.ofSeconds(10));

    @Autowired
    public TodoistHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_BASE_URL);
    }

    /** Test seam: points the client at a local stub server. */
    TodoistHttpClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    /** GET {@code path} (relative to the API base, incl. query string). */
    public Object get(String path, String token) {
        return send(request(path, token).GET().build(), token);
    }

    /** POST a JSON body ({@code null} = no body); a 204 answer (e.g. closing a task) returns {@code null}. */
    public Object post(String path, Object body, String token) {
        HttpRequest.Builder builder = request(path, token);
        if (body == null) {
            return send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(), token);
        }
        return send(builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), token);
    }

    private HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private Object send(HttpRequest request, String token) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Todoist API request interrupted");
        } catch (Exception e) {
            throw new IllegalStateException("Todoist API request failed: " + scrub(e.getMessage(), token));
        }
        checkResponse(response);
        String body = response.body();
        if (response.statusCode() == 204 || body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Todoist API returned no valid JSON (HTTP " + response.statusCode() + ")");
        }
    }

    private void checkResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status < 400) return;
        JsonNode error = readError(response.body());
        if (status == 401) {
            throw new IllegalStateException("Todoist authentication failed — check the API token");
        }
        if (status == 429) {
            String retryAfter = error.path("error_extra").path("retry_after").asText(
                    response.headers().firstValue("Retry-After").orElse(""));
            throw new IllegalStateException("Todoist rate limit reached (HTTP 429)"
                    + (retryAfter.isBlank() ? "" : " — retry after " + retryAfter + " s"));
        }
        String message = error.path("error").asText("");
        String tag = error.path("error_tag").asText("");
        StringBuilder sb = new StringBuilder("Todoist API error ").append(status);
        if (!message.isBlank()) sb.append(": ").append(message);
        if (!tag.isBlank()) sb.append(" (").append(tag).append(")");
        throw new IllegalStateException(sb.toString());
    }

    private JsonNode readError(String body) {
        if (body == null || body.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(body);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Todoist request body");
        }
    }

    static String scrub(String message, String token) {
        if (message == null) return "connection error";
        return token == null || token.isEmpty() ? message : message.replace(token, "***");
    }
}
