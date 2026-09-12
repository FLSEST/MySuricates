package com.automation.service.integration.providers.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer for the GitHub REST API: bearer auth, API-version header, JSON (de)serialisation
 * and the error mapping the provider relies on (401 → PAT hint, other 4xx/5xx → status + body).
 */
@Component
@RequiredArgsConstructor
public class GitHubHttpClient {

    static final String API_VERSION = "2022-11-28";

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

    public <T> T patch(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    /** Checked response including headers (e.g. {@code X-OAuth-Scopes}); errors map like {@link #get}. */
    public HttpResponse<String> getRaw(String url, String token) {
        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder(url, token).GET().build(), HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GitHub API request failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder requestBuilder(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15));
    }

    private <T> T send(HttpRequest request, Class<T> responseType) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GitHub API request failed: " + e.getMessage(), e);
        }
    }

    private static void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            throw new IllegalStateException("GitHub authentication failed — check the Personal Access Token");
        }
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("GitHub API error " + response.statusCode() + ": " + response.body());
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize GitHub request body: " + e.getMessage(), e);
        }
    }
}
