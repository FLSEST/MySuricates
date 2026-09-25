package com.automation.service.integration.providers.openmeteo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider + HTTP client against a local stand-in for the two Open-Meteo endpoints. */
class OpenMeteoProviderTest {

    private HttpServer server;
    private OpenMeteoProvider provider;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new OpenMeteoProvider(new OpenMeteoHttpClient(new ObjectMapper(),
                base + "/v1/forecast", base + "/v1/search"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String contextPath, int status, String body) {
        server.createContext(contextPath, ex -> {
            path.set(ex.getRequestURI().getPath());
            query.set(ex.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void forecast_callsForecastPathAndReturnsJson() {
        respond("/v1/forecast", 200, "{\"latitude\":47.38,\"timezone\":\"Europe/Zurich\",\"daily\":{\"time\":[\"2026-09-24\"]}}");

        Object result = provider.executeAction("forecast", Map.of("latitude", 47.3769, "longitude", 8.5417, "days", 3), null);

        assertThat(path.get()).isEqualTo("/v1/forecast");
        assertThat(query.get()).startsWith("latitude=47.3769&longitude=8.5417&forecast_days=3&").endsWith("&timezone=auto");
        assertThat(((Map<?, ?>) result).get("timezone")).isEqualTo("Europe/Zurich");
    }

    @Test
    void forecast_invalidParamsFailBeforeAnyRequest() {
        respond("/v1/forecast", 200, "{}");

        assertThatThrownBy(() -> provider.executeAction("forecast", Map.of("latitude", 91, "longitude", 8), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(path.get()).isNull();
    }

    @Test
    void forecast_apiErrorCarriesReason() {
        respond("/v1/forecast", 400, "{\"error\":true,\"reason\":\"Cannot initialize WeatherVariable from invalid String value foo\"}");

        assertThatThrownBy(() -> provider.executeAction("forecast", Map.of("latitude", 47, "longitude", 8, "hourly", "foo"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Open-Meteo error 400")
                .hasMessageContaining("invalid String value foo");
    }

    @Test
    void forecast_nonJsonErrorKeepsBody() {
        respond("/v1/forecast", 502, "Bad Gateway");

        assertThatThrownBy(() -> provider.executeAction("forecast", Map.of("latitude", 47, "longitude", 8), null))
                .hasMessageContaining("502").hasMessageContaining("Bad Gateway");
    }

    @Test
    void geocode_callsSearchPath() {
        respond("/v1/search", 200, "{\"results\":[{\"name\":\"Zürich\",\"latitude\":47.36667,\"longitude\":8.55}]}");

        Object result = provider.executeAction("geocode", Map.of("name", "Zürich", "count", 1), null);

        assertThat(path.get()).isEqualTo("/v1/search");
        assertThat(query.get()).isEqualTo("name=Z%C3%BCrich&count=1&language=de&format=json");
        assertThat((List<?>) ((Map<?, ?>) result).get("results")).hasSize(1);
    }

    @Test
    void geocode_noMatchYieldsEmptyResults() {
        respond("/v1/search", 200, "{\"generationtime_ms\":0.5}");

        Map<?, ?> result = (Map<?, ?>) provider.executeAction("geocode", Map.of("name", "Xyzzyqq"), null);

        assertThat(result.get("results")).isEqualTo(List.of());
    }

    @Test
    void unknownActionAndMetadata() {
        assertThatThrownBy(() -> provider.executeAction("history", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(provider.getProviderId()).isEqualTo("open-meteo");
        assertThat(provider.requiresCredentials()).isFalse();
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getAvailableActions()).extracting("name").containsExactly("forecast", "geocode");
        // Kontoname ohne Sprache wie beim iCal-Feed – die Oberfläche zeigt ihn unverändert, auch auf Englisch (6e1e87c2)
        assertThat(provider.fetchAccountInfo("|")).isEqualTo("{\"name\":\"Open-Meteo\"}");
    }

    @Test
    void productionClientUsesOpenMeteoHosts() {
        assertThat(OpenMeteoHttpClient.FORECAST_URL).isEqualTo("https://api.open-meteo.com/v1/forecast");
        assertThat(OpenMeteoHttpClient.GEOCODING_URL).isEqualTo("https://geocoding-api.open-meteo.com/v1/search");
    }
}
