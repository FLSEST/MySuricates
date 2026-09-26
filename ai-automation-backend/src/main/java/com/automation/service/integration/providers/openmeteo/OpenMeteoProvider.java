package com.automation.service.integration.providers.openmeteo;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Open-Meteo integration provider — weather forecast and place search (geocoding), free and without
 * an API key ({@link #requiresCredentials()} is {@code false}). Responses are passed through as the
 * API returns them; parameters are validated before any request (coordinates, days 1–16, variable names).
 */
@Component
@RequiredArgsConstructor
public class OpenMeteoProvider implements IntegrationProvider {

    private final OpenMeteoHttpClient http;

    @Override
    public String getProviderId() {
        return "open-meteo";
    }

    @Override
    public String getDisplayName() {
        return "Open-Meteo";
    }

    @Override
    public String getIcon() {
        return "CloudSun";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.open-meteo.description (DE/EN).
        return "Wettervorhersage und Ortssuche über Open-Meteo, ohne API-Key";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig("", "", List.of(), "", "");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public boolean requiresCredentials() {
        return false;
    }

    /** Runs without a stored connection (TODO cdccc1e5) — see {@link IntegrationProvider#connectionOptional()}. */
    @Override
    public boolean connectionOptional() {
        return true;
    }

    /** Nothing to validate — a static marker so saving (empty) credentials yields CONNECTED. */
    @Override
    public String fetchAccountInfo(String accessToken) {
        return "{\"name\":\"Open-Meteo\"}";
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        Map<String, Object> forecastProps = new LinkedHashMap<>();
        forecastProps.put("latitude", Map.of("type", "number", "description", "Breitengrad (-90 bis 90)"));
        forecastProps.put("longitude", Map.of("type", "number", "description", "Längengrad (-180 bis 180)"));
        forecastProps.put("days", Map.of("type", "integer", "default", OpenMeteoParams.DEFAULT_DAYS,
                "description", "Vorhersagetage (1-" + OpenMeteoParams.MAX_DAYS + ")"));
        forecastProps.put("hourly", Map.of("type", "string", "default", OpenMeteoParams.DEFAULT_HOURLY,
                "description", "Stündliche Variablen (kommagetrennt, leer = keine)"));
        forecastProps.put("daily", Map.of("type", "string", "default", OpenMeteoParams.DEFAULT_DAILY,
                "description", "Tägliche Variablen (kommagetrennt, leer = keine)"));
        forecastProps.put("current", Map.of("type", "string", "default", OpenMeteoParams.DEFAULT_CURRENT,
                "description", "Aktuelle Werte (kommagetrennt, leer = keine)"));
        forecastProps.put("timezone", Map.of("type", "string", "default", "auto", "description", "Zeitzone, z.B. Europe/Zurich oder auto"));
        forecastProps.put("temperature_unit", Map.of("type", "string", "description", "celsius (Standard) oder fahrenheit"));
        forecastProps.put("wind_speed_unit", Map.of("type", "string", "description", "kmh (Standard), ms, mph oder kn"));
        forecastProps.put("precipitation_unit", Map.of("type", "string", "description", "mm (Standard) oder inch"));
        return List.of(
                new IntegrationAction("forecast", "Wettervorhersage für Koordinaten abrufen",
                        Map.of("type", "object", "properties", forecastProps, "required", List.of("latitude", "longitude"))),
                new IntegrationAction("geocode", "Orte nach Namen suchen (Koordinaten, Land, Zeitzone)",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "name", Map.of("type", "string", "description", "Ortsname oder Postleitzahl (mind. 2 Zeichen)"),
                                        "count", Map.of("type", "integer", "default", 10, "description", "Max. Treffer (1-100)"),
                                        "language", Map.of("type", "string", "default", "de", "description", "Sprache der Namen (z.B. de, en)")
                                ),
                                "required", List.of("name"))));
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        return switch (action) {
            case "forecast" -> http.forecast(OpenMeteoParams.forecastQuery(params));
            case "geocode" -> {
                Map<String, Object> result = new LinkedHashMap<>(http.geocode(OpenMeteoParams.geocodeQuery(params)));
                result.putIfAbsent("results", List.of()); // the API omits the key when nothing matches
                yield result;
            }
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }
}
