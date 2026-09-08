package com.automation.service.staging;

import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the transport contract before extraction (Audit-TODO 189ad41b). Uses the JDK's
 * {@link HttpServer} on an ephemeral port — no extra dependency.
 */
@ExtendWith(MockitoExtension.class)
class InstanceHttpClientTest {

    @Mock private ApiKeyService apiKeyService;

    private InstanceHttpClient client;
    private HttpServer server;
    private String base;
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private volatile String lastBody = "";

    @BeforeEach
    void setUp() throws IOException {
        client = new InstanceHttpClient(apiKeyService, new ObjectMapper());
        lenient().when(apiKeyService.getEffectiveApiKey()).thenReturn("effective-key");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastHeaders.clear();
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), String.join(",", v)));
            lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            byte[] out;
            int status;
            if (path.endsWith("/ok")) { status = 200; out = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8); }
            else if (path.endsWith("/created")) { status = 201; out = "{\"id\":\"new\"}".getBytes(StandardCharsets.UTF_8); }
            else if (path.endsWith("/empty")) { status = 204; out = new byte[0]; }
            else if (path.endsWith("/boom")) { status = 500; out = "server exploded".getBytes(StandardCharsets.UTF_8); }
            else { status = 404; out = "nope".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(status, status == 204 ? -1 : out.length);
            if (status != 204) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void httpGet_200_returnsJson_withEffectiveKeyAndInternalSyncHeader() {
        JsonNode node = client.httpGet(base + "/ok");

        assertThat(node.path("hello").asText()).isEqualTo("world");
        assertThat(lastHeaders).containsEntry("x-api-key", "effective-key").containsEntry("x-internal-sync", "true");
        assertThat(lastHeaders.get("accept")).isEqualTo("application/json");
    }

    @Test
    void httpGet_non200_orUnreachable_returnsNull() {
        assertThat(client.httpGet(base + "/missing")).isNull();
        assertThat(client.httpGet("http://127.0.0.1:1/unreachable")).isNull();
    }

    @Test
    void httpPost_2xx_returnsBody_andSendsJson() {
        JsonNode node = client.httpPost(base + "/created", Map.of("name", "x"));

        assertThat(node.path("id").asText()).isEqualTo("new");
        assertThat(lastBody).isEqualTo("{\"name\":\"x\"}");
        assertThat(lastHeaders).containsEntry("content-type", "application/json");
    }

    @Test
    void httpPut_2xxWithoutBody_returnsNull() {
        assertThat(client.httpPut(base + "/empty", Map.of())).isNull();
    }

    @Test
    void httpPost_non2xx_throwsWithStatusAndBody() {
        assertThatThrownBy(() -> client.httpPost(base + "/boom", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("HTTP 500: server exploded");
    }

    @Test
    void httpPost_unreachable_wrapsCauseWithMethodAndUrl() {
        assertThatThrownBy(() -> client.httpPost("http://127.0.0.1:1/x", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("POST http://127.0.0.1:1/x failed: ");
    }

    @Test
    void overrideKey_winsOverEffectiveKey_andIsPerThread_untilCleared() throws Exception {
        client.setTargetApiKeyOverride("override-key");
        client.httpGet(base + "/ok");
        assertThat(lastHeaders).containsEntry("x-api-key", "override-key");

        Map<String, String> otherThreadHeaders = new ConcurrentHashMap<>();
        Thread t = new Thread(() -> {
            client.httpGet(base + "/ok");
            otherThreadHeaders.putAll(lastHeaders);
        });
        t.start();
        t.join();
        assertThat(otherThreadHeaders).containsEntry("x-api-key", "effective-key");

        client.clearTargetApiKeyOverride();
        client.httpGet(base + "/ok");
        assertThat(lastHeaders).containsEntry("x-api-key", "effective-key");
    }

    @Test
    void addApiKeyHeader_blankKey_omitsApiKeyHeader_butAlwaysMarksInternalSync() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("  ");
        HttpURLConnection conn = mock(HttpURLConnection.class);

        client.addApiKeyHeader(conn);

        verify(conn, never()).setRequestProperty(org.mockito.ArgumentMatchers.eq("X-API-Key"), org.mockito.ArgumentMatchers.anyString());
        verify(conn).setRequestProperty("X-Internal-Sync", "true");
    }
}
