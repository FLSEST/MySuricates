package com.automation.service.integration.providers.gitea;

import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.automation.service.integration.OutboundTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP layer for the Gitea/Forgejo REST API ({@code /api/v1}): {@code Authorization: token}
 * header, JSON (de)serialisation and the error mapping the provider relies on (401 → token hint,
 * other 4xx/5xx → status + shortened body). The token never appears in an error message.
 * The server is an {@link OutboundTarget#ownService() own service}: local targets are refused only
 * with {@code private-network-guard-own-services} on.
 */
@Component
public class GiteaHttpClient {

    private static final int MAX_ERROR_BODY = 500;

    private final ObjectMapper objectMapper;
    private final OutboundClient httpClient;

    public GiteaHttpClient(ObjectMapper objectMapper, OutboundHttp outbound) {
        this.objectMapper = objectMapper;
        this.httpClient = outbound.client(OutboundTarget.ownService(), Duration.ofSeconds(10));
    }

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

    public <T> T put(String url, Object body, String token, Class<T> responseType) {
        return send(requestBuilder(url, token)
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(body))).build(), responseType);
    }

    private HttpRequest.Builder requestBuilder(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "token " + token)
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
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e; // includes a target refused by the outbound guard
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gitea API request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Gitea API request failed: " + e.getMessage(), e);
        }
    }

    private static void checkResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 401) {
            throw new IllegalStateException("Gitea authentication failed — check the access token");
        }
        if (status >= 400) {
            throw new IllegalStateException("Gitea API error " + status + ": " + shorten(response.body()));
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
            throw new IllegalStateException("Failed to serialize Gitea request body: " + e.getMessage(), e);
        }
    }
}
