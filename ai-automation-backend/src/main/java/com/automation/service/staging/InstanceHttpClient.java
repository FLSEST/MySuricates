package com.automation.service.staging;

import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Plain HTTP transport to another MySuricates instance (Stable / Test): GET/POST/PUT with JSON
 * bodies, the {@code X-API-Key} header (per-thread override or the effective key) and the
 * {@code X-Internal-Sync} marker. {@link #httpGet} is lenient (null on any failure),
 * {@link #httpPost}/{@link #httpPut} throw on non-2xx.
 *
 * <p>Extracted from {@code InstanceSyncHelper} (Audit-TODO 189ad41b). Singleton — the override
 * is an instance-level {@link ThreadLocal}, so set/clear on the calling thread behaves as before.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceHttpClient {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    /** Overrides the API key for outgoing HTTP calls within the current thread (used for Stable/Test targets). */
    private final ThreadLocal<String> targetApiKeyOverride = new ThreadLocal<>();

    /** Sets the API key override for the current thread. Call {@link #clearTargetApiKeyOverride()} in a finally block. */
    public void setTargetApiKeyOverride(String apiKey) {
        targetApiKeyOverride.set(apiKey);
    }

    /** Clears the API key override for the current thread. */
    public void clearTargetApiKeyOverride() {
        targetApiKeyOverride.remove();
    }

    public JsonNode httpGet(String url) {
        return httpGet(url, 10_000, 30_000);
    }

    /** {@link #httpGet(String)} with a short timeout for status probes (connect and read each {@code timeoutMs}). */
    public JsonNode httpGet(String url, int timeoutMs) {
        return httpGet(url, timeoutMs, timeoutMs);
    }

    private JsonNode httpGet(String url, int connectTimeoutMs, int readTimeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            addApiKeyHeader(conn);
            int code = conn.getResponseCode();
            if (code == 200) {
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                return objectMapper.readTree(body);
            }
            conn.disconnect();
            return null;
        } catch (Exception e) {
            log.warn("GET {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    public JsonNode httpPost(String url, Object body) {
        return httpSend("POST", url, body);
    }

    public JsonNode httpPut(String url, Object body) {
        return httpSend("PUT", url, body);
    }

    private JsonNode httpSend(String method, String url, Object body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            addApiKeyHeader(conn);
            conn.setDoOutput(true);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                return responseBody.isEmpty() ? null : objectMapper.readTree(responseBody);
            } else {
                String errorBody = "";
                try {
                    errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                conn.disconnect();
                throw new RuntimeException("HTTP " + code + ": " + errorBody);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(method + " " + url + " failed: " + e.getMessage(), e);
        }
    }

    void addApiKeyHeader(HttpURLConnection conn) {
        String apiKey = targetApiKeyOverride.get();
        if (apiKey == null) {
            apiKey = apiKeyService.getEffectiveApiKey();
        }
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty(API_KEY_HEADER, apiKey);
        }
        conn.setRequestProperty("X-Internal-Sync", "true");
    }
}
