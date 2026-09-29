package com.automation.service.integration.providers.openmeteo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenMeteoHttpClientTest {

    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private HttpServer server;
    private OpenMeteoHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new OpenMeteoHttpClient(new ObjectMapper(), base + "/forecast", base + "/geo");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String response) {
        server.createContext(path, ex -> {
            lastUri.set(ex.getRequestURI().toString());
            accept.set(ex.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void forecastAndGeocode_hitTheirOwnEndpointWithQuery() {
        respond("/forecast", 200, "{\"kind\":\"forecast\"}");
        respond("/geo", 200, "{\"kind\":\"geo\"}");

        assertThat(client.forecast("latitude=1&longitude=2")).containsEntry("kind", "forecast");
        assertThat(lastUri.get()).isEqualTo("/forecast?latitude=1&longitude=2");
        assertThat(accept.get()).isEqualTo("application/json");

        assertThat(client.geocode("name=Zurich")).containsEntry("kind", "geo");
        assertThat(lastUri.get()).isEqualTo("/geo?name=Zurich");
    }

    @Test
    void errorWithReason_isReportedWithStatusAndReason() {
        respond("/forecast", 400, "{\"error\":true,\"reason\":\"Latitude must be in range\"}");

        assertThatThrownBy(() -> client.forecast("latitude=99"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Open-Meteo error 400: Latitude must be in range");
    }

    @Test
    void errorWithoutReason_fallsBackToRawBodyTruncated() {
        respond("/forecast", 502, "  gateway down  ");
        assertThatThrownBy(() -> client.forecast("x")).hasMessage("Open-Meteo error 502: gateway down");

        server.removeContext("/forecast");
        respond("/forecast", 500, "y".repeat(400));
        assertThatThrownBy(() -> client.forecast("x")).hasMessage("Open-Meteo error 500: " + "y".repeat(300) + "…");

        server.removeContext("/forecast");
        respond("/forecast", 500, "{\"error\":true}");
        assertThatThrownBy(() -> client.forecast("x")).hasMessage("Open-Meteo error 500: {\"error\":true}");
    }

    @Test
    void invalidJsonAndUnreachableServer_becomeIllegalState() {
        respond("/forecast", 200, "<html>");
        assertThatThrownBy(() -> client.forecast("x"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Open-Meteo request failed");

        OpenMeteoHttpClient dead = new OpenMeteoHttpClient(new ObjectMapper(), "http://127.0.0.1:1/f", "http://127.0.0.1:1/g");
        assertThatThrownBy(() -> dead.geocode("x"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Open-Meteo request failed");
    }
}
