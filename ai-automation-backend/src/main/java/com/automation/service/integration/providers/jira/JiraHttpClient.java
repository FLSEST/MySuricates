package com.automation.service.integration.providers.jira;

import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer for the Jira Cloud REST API (v3): bearer auth, JSON (de)serialisation and the
 * error mapping the provider relies on.
 *
 * <p>Differs from {@code GitHubHttpClient} in ways that matter: no {@code Accept} header is sent,
 * there is no 401 special case (every {@code >= 400} response maps to the same
 * "Jira API error &lt;status&gt;: &lt;body&gt;" message), and {@link #put} / {@link #postNoResponse}
 * never parse a response body — Jira's write endpoints reply with an empty 204/204-like body.
 */
@Component
@RequiredArgsConstructor
public class JiraHttpClient {

    private final ObjectMapper objectMapper;
    private final OutboundClient httpClient = OutboundHttp.fixedHost(Duration.ofSeconds(10));

    public <T> T get(String url, String token, Class<T> responseType) {
        try {
            HttpRequest request = requestBuilder(url, token).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    public <T> T post(String url, Object body, String token, Class<T> responseType) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = requestBuilder(url, token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkResponse(response);
            return objectMapper.readValue(response.body(), responseType);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    /** Fires the request but never parses a response body — used for transitions, which reply with 204. */
    public void postNoResponse(String url, Object body, String token) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = requestBuilder(url, token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    /** Fires the request but never parses a response body — Jira's issue-update endpoint replies with 204. */
    public void put(String url, Object body, String token) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = requestBuilder(url, token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                checkResponse(response);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Raw, unchecked GET with a caller-supplied timeout — used for the accessible-resources call,
     * which passes a 10 s request timeout (the other methods hardcode 15 s). Its non-200/empty-body
     * handling lives in {@code JiraProvider.resolveCloudId} — that method must not throw, only log
     * and fall back to {@code null} — so this method neither checks the status code nor parses the
     * body. Transport failures are wrapped like the other calls ("Jira API request failed" prefix).
     */
    public HttpResponse<String> getRaw(String url, String token, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .timeout(timeout)
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Jira API request failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder requestBuilder(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(15));
    }

    private static void checkResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Jira API error " + response.statusCode() + ": " + response.body());
        }
    }
}
