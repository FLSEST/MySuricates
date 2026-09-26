package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JiraHttpClientTest {

    private HttpServer server;
    private String base;
    private final JiraHttpClient client = new JiraHttpClient(new ObjectMapper());

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

    private void respond(String path, int status, String body) {
        respond(path, status, body, null, null);
    }

    private void respond(String path, int status, String body, AtomicReference<String> capturedAuth,
                          AtomicReference<String> capturedAcceptHeader) {
        server.createContext(path, ex -> {
            if (capturedAuth != null) capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (capturedAcceptHeader != null) capturedAcceptHeader.set(ex.getRequestHeaders().getFirst("Accept"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    // --- checkResponse cases (migrated from JiraProviderTest, same assertions) ---
    // Jira has no 401 special case — every >= 400 status maps to the same generic message.

    @Test
    void get_400_throwsIllegalState() {
        respond("/x", 400, "Bad request");
        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400");
    }

    @Test
    void get_401_throwsGenericError() {
        respond("/x", 401, "Unauthorized");
        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void get_403_throws() {
        respond("/x", 403, "Forbidden");
        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }

    @Test
    void get_500_throws() {
        respond("/x", 500, "Server Error");
        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void get_200_doesNotThrow() {
        respond("/x", 200, "{}"); // valid JSON: get() also parses the body, so "OK" would fail for the wrong reason
        assertThatNoException().isThrownBy(() -> client.get(base + "/x", "t", Object.class));
    }

    // --- Jira-specific request shape ---

    @Test
    void get_sendsBearerAndNoAccept() {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        respond("/myself", 200, "{\"displayName\":\"Alex\"}", auth, accept);

        Object user = client.get(base + "/myself", "tok", Object.class);

        assertThat(((Map<?, ?>) user).get("displayName")).isEqualTo("Alex");
        assertThat(auth.get()).isEqualTo("Bearer tok");
        assertThat(accept.get()).isNull();
    }

    @Test
    void put_doesNotParseBody() {
        // Body is deliberately not valid JSON — proves put() never attempts to deserialize it.
        respond("/issue/PROJ-1", 204, "not-json");
        assertThatNoException().isThrownBy(() -> client.put(base + "/issue/PROJ-1", Map.of("fields", Map.of()), "t"));
    }

    @Test
    void postNoResponse_noBodyParsing() {
        respond("/issue/PROJ-1/transitions", 204, "not-json");
        assertThatNoException().isThrownBy(() ->
                client.postNoResponse(base + "/issue/PROJ-1/transitions", Map.of("transition", Map.of("id", "31")), "t"));
    }

    @Test
    void put_stillThrowsOnErrorStatus() {
        respond("/issue/PROJ-1", 400, "Bad request");
        assertThatThrownBy(() -> client.put(base + "/issue/PROJ-1", Map.of("fields", Map.of()), "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400");
    }

    // --- getRaw (accessible-resources) ---

    @Test
    void getRaw_usesGivenTimeout() {
        respond("/oauth/token/accessible-resources", 200, "[{\"id\":\"cloud-1\"}]");

        HttpResponse<String> response = client.getRaw(
                base + "/oauth/token/accessible-resources", "t", Duration.ofSeconds(5));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("cloud-1");
    }

    @Test
    void getRaw_doesNotThrowOnErrorStatus() {
        // resolveCloudId handles non-200 itself (log + null) — getRaw must hand the raw response back.
        respond("/oauth/token/accessible-resources", 500, "boom");

        HttpResponse<String> response = client.getRaw(
                base + "/oauth/token/accessible-resources", "t", Duration.ofSeconds(5));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("boom");
    }

    // --- Transport failures ---

    @Test
    void connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.get("http://127.0.0.1:1/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Jira API request failed: ");
    }

    @Test
    void getRaw_connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.getRaw("http://127.0.0.1:1/x", "t", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Jira API request failed: ");
    }
}
