package com.automation.service.integration.providers.ntfy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin HTTP layer for a ntfy server: JSON in and out, a bearer header only when a token is set,
 * and the error mapping the provider relies on (401/403 → token hint, other 4xx/5xx → status + body).
 */
@Component
@RequiredArgsConstructor
public class NtfyHttpClient {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int MAX_ERROR_BODY = 300;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public Map<String, Object> get(String url, String token) {
        return send(builder(url, token).GET().build());
    }

    public Map<String, Object> postJson(String url, Object body, String token) {
        return send(builder(url, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body))).build());
    }

    private static HttpRequest.Builder builder(String url, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15));
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private Map<String, Object> send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            String body = response.body();
            return body == null || body.isBlank() ? Map.of() : objectMapper.readValue(body, MAP);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ntfy request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("ntfy request failed: " + e.getMessage(), e);
        }
    }

    private static void checkResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new IllegalStateException("ntfy rejected the request (HTTP " + status
                    + ") — check the access token and the topic's access rights");
        }
        if (status >= 400) {
            String body = response.body() == null ? "" : response.body().strip();
            if (body.length() > MAX_ERROR_BODY) body = body.substring(0, MAX_ERROR_BODY) + "…";
            throw new IllegalStateException("ntfy error " + status + ": " + body);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize ntfy request body: " + e.getMessage(), e);
        }
    }
}
