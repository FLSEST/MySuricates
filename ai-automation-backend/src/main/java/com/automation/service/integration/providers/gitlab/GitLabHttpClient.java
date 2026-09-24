package com.automation.service.integration.providers.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer for the GitLab REST API v4: {@code PRIVATE-TOKEN} header, JSON (de)serialisation
 * and the error mapping the provider relies on (401 → token hint, 403 → scope hint, other 4xx/5xx →
 * status + shortened body). The token never appears in an error message.
 */
@Component
@RequiredArgsConstructor
public class GitLabHttpClient {

    private static final int MAX_ERROR_BODY = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public <T> T get(String url, String token, Class<T> responseType) {
        return send(requestBuilder(url, token).GET().build(), responseType);
    }

    public <T> T post(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    public <T> T put(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    private HttpRequest.Builder requestBuilder(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            String body = response.body();
            if (body == null || body.isBlank()) return null;
            return objectMapper.readValue(body, responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitLab API request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("GitLab API request failed: " + e.getMessage(), e);
        }
    }

    private static void checkResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401) {
            throw new IllegalStateException("GitLab authentication failed — check the access token");
        }
        if (status == 403) {
            throw new IllegalStateException("GitLab denied the request (403) — the token may lack the 'api' scope "
                    + "or project access: " + shorten(response.body()));
        }
        if (status >= 400) {
            throw new IllegalStateException("GitLab API error " + status + ": " + shorten(response.body()));
        }
    }

    static String shorten(String body) {
        if (body == null) return "";
        return body.length() <= MAX_ERROR_BODY ? body : body.substring(0, MAX_ERROR_BODY) + "…";
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GitLab request body: " + e.getMessage(), e);
        }
    }
}
