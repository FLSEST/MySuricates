package com.automation.service.integration.providers.ntfy;

import com.automation.testsupport.TestTargetGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NtfyHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NtfyHttpClient client = new NtfyHttpClient(objectMapper, TestTargetGuards.loopbackOnly());
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(int status, String response) {
        server.createContext("/", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            method.set(ex.getRequestMethod());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void get_sendsBearerOnlyWithTokenAndParsesJson() {
        respond(200, "{\"a\":1}");

        assertThat(client.get(base + "/x", "tk")).containsEntry("a", 1);
        assertThat(auth.get()).isEqualTo("Bearer tk");
        assertThat(method.get()).isEqualTo("GET");

        client.get(base + "/x", " ");
        assertThat(auth.get()).isNull();
        client.get(base + "/x", null);
        assertThat(auth.get()).isNull();
    }

    @Test
    void postJson_serializesBodyWithJsonContentType() throws Exception {
        respond(200, "{\"id\":\"z\"}");

        Map<String, Object> result = client.postJson(base + "/", Map.of("topic", "t"), null);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(objectMapper.readValue(body.get(), Map.class)).containsEntry("topic", "t");
        assertThat(result).containsEntry("id", "z");
    }

    @Test
    void emptyResponseBodyYieldsEmptyMap() {
        respond(200, "");
        assertThat(client.get(base + "/", null)).isEmpty();
    }

    @Test
    void unauthorizedAndForbiddenMapToTokenHint() {
        respond(401, "nope");
        assertThatThrownBy(() -> client.get(base + "/", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401").hasMessageContaining("access token");
        server.removeContext("/");
        respond(403, "nope");
        assertThatThrownBy(() -> client.get(base + "/", "t")).hasMessageContaining("HTTP 403");
    }

    @Test
    void otherErrorsCarryStatusAndTruncatedBody() {
        respond(500, "  boom  ");
        assertThatThrownBy(() -> client.get(base + "/", null)).hasMessage("ntfy error 500: boom");

        server.removeContext("/");
        respond(400, "x".repeat(400));
        assertThatThrownBy(() -> client.get(base + "/", null))
                .hasMessage("ntfy error 400: " + "x".repeat(300) + "…");
    }

    @Test
    void invalidJsonAndUnreachableServerBecomeIllegalState() {
        respond(200, "<html>");
        assertThatThrownBy(() -> client.get(base + "/", null))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("ntfy request failed");

        NtfyHttpClient dead = new NtfyHttpClient(objectMapper, TestTargetGuards.loopbackOnly());
        assertThatThrownBy(() -> dead.get("http://127.0.0.1:1/", null))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("ntfy request failed");
    }
}
