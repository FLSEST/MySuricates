package com.automation.service.integration.providers.telegram;

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

/** The Telegram HTTP layer against a local stub: URL building, error mapping, token scrubbing. */
class TelegramHttpClientTest {

    private static final String TOKEN = "123456789:AAHabcdefghijklmnopqrstuvwxyz0123";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private HttpServer server;
    private TelegramHttpClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new TelegramHttpClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(int status, String response) {
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void call_postsJsonToBotPathAndReturnsResult() throws Exception {
        respond(200, "{\"ok\":true,\"result\":{\"message_id\":7}}");

        Object result = client.call(TOKEN, "sendMessage", Map.of("chat_id", "42", "text", "hi"));

        assertThat(path.get()).isEqualTo("/bot" + TOKEN + "/sendMessage");
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(objectMapper.readValue(body.get(), Map.class)).containsEntry("chat_id", "42").containsEntry("text", "hi");
        assertThat(result).isEqualTo(Map.of("message_id", 7));
    }

    @Test
    void call_malformedOrMissingTokenIsRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> client.call("not-a-token", "getMe", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid format")
                .hasMessageNotContaining("not-a-token");
        assertThatThrownBy(() -> client.call(null, "getMe", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(path.get()).isNull();
    }

    @Test
    void call_401MapsToAuthenticationHint() {
        respond(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

        assertThatThrownBy(() -> client.call(TOKEN, "getMe", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authentication failed");
    }

    @Test
    void call_429ReportsRetryAfter() {
        respond(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\",\"parameters\":{\"retry_after\":12}}");

        assertThatThrownBy(() -> client.call(TOKEN, "sendMessage", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate limit")
                .hasMessageContaining("retry after 12 s");
    }

    @Test
    void call_otherErrorCarriesCodeAndScrubbedDescription() {
        respond(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: bad " + TOKEN + "\"}");

        assertThatThrownBy(() -> client.call(TOKEN, "sendMessage", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Telegram API error 400: Bad Request: bad ***")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void call_nonJsonBodyIsReportedByStatus() {
        respond(502, "<html>bad gateway</html>");
        assertThatThrownBy(() -> client.call(TOKEN, "getMe", Map.of()))
                .hasMessage("Telegram API error 502");

        server.removeContext("/");
        respond(200, "oops");
        assertThatThrownBy(() -> client.call(TOKEN, "getMe", Map.of()))
                .hasMessageContaining("no valid JSON (HTTP 200)");
    }

    @Test
    void call_transportFailureNeverLeaksTokenOrCause() {
        TelegramHttpClient dead = new TelegramHttpClient(objectMapper, "http://127.0.0.1:1");

        assertThatThrownBy(() -> dead.call(TOKEN, "getMe", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Telegram API request failed")
                .hasMessageNotContaining(TOKEN)
                .hasNoCause();
    }

    @Test
    void scrub_replacesTokenAndHandlesEmptyMessages() {
        assertThat(TelegramHttpClient.scrub("x " + TOKEN + " y", TOKEN)).isEqualTo("x *** y");
        assertThat(TelegramHttpClient.scrub(null, TOKEN)).isEmpty();
        assertThat(TelegramHttpClient.scrub("  ", TOKEN)).isEmpty();
    }
}
