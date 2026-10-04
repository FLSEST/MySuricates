package com.automation.service.integration.providers.openmeteo;

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

/**
 * HTTP layer for the two Open-Meteo endpoints (weather forecast, geocoding). No key needed. The base
 * URLs are constructor arguments so tests can point the client at a local server.
 */
@Component
public class OpenMeteoHttpClient {

    static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int MAX_ERROR_BODY = 300;

    private final ObjectMapper objectMapper;
    private final String forecastUrl;
    private final String geocodingUrl;
    private final OutboundClient httpClient = OutboundHttp.fixedHost(Duration.ofSeconds(10));

    @Autowired
    public OpenMeteoHttpClient(ObjectMapper objectMapper) {
        this(objectMapper, FORECAST_URL, GEOCODING_URL);
    }

    OpenMeteoHttpClient(ObjectMapper objectMapper, String forecastUrl, String geocodingUrl) {
        this.objectMapper = objectMapper;
        this.forecastUrl = forecastUrl;
        this.geocodingUrl = geocodingUrl;
    }

    public Map<String, Object> forecast(String query) {
        return get(forecastUrl + "?" + query);
    }

    public Map<String, Object> geocode(String query) {
        return get(geocodingUrl + "?" + query);
    }

    private Map<String, Object> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) throw new IllegalStateException(errorMessage(response));
            return objectMapper.readValue(response.body(), MAP);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Open-Meteo request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Open-Meteo request failed: " + e.getMessage(), e);
        }
    }

    /** Open-Meteo answers errors as {@code {"error":true,"reason":"..."}}; fall back to the raw body. */
    private String errorMessage(HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body().strip();
        try {
            Object reason = objectMapper.readValue(body, MAP).get("reason");
            if (reason != null) return "Open-Meteo error " + response.statusCode() + ": " + reason;
        } catch (Exception ignored) {
            // not JSON — use the raw body below
        }
        if (body.length() > MAX_ERROR_BODY) body = body.substring(0, MAX_ERROR_BODY) + "…";
        return "Open-Meteo error " + response.statusCode() + ": " + body;
    }
}
