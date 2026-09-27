package com.automation.service.integration.providers.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubHttpClientTest {

    private HttpServer server;
    private String base;
    private final GitHubHttpClient client = new GitHubHttpClient(new ObjectMapper());

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

    private void respond(String path, int status, String body, AtomicReference<String> capturedAuth,
                         AtomicReference<String> capturedBody) {
        respond(path, status, body, capturedAuth, capturedBody, null);
    }

    private void respond(String path, int status, String body, AtomicReference<String> capturedAuth,
                         AtomicReference<String> capturedBody, AtomicReference<String> capturedMethod) {
        server.createContext(path, ex -> {
            if (capturedAuth != null) capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (capturedBody != null) capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (capturedMethod != null) capturedMethod.set(ex.getRequestMethod());
            ex.getResponseHeaders().add("X-OAuth-Scopes", "repo, read:org");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void get_sendsBearerTokenAndParsesJson() {
        AtomicReference<String> auth = new AtomicReference<>();
        respond("/user", 200, "{\"login\":\"alex\"}", auth, null);

        Object user = client.get(base + "/user", "ghp_x", Object.class);

        assertThat(user).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) user).get("login")).isEqualTo("alex");
        assertThat(auth.get()).isEqualTo("Bearer ghp_x");
    }

    @Test
    void post_serialisesBody() {
        AtomicReference<String> body = new AtomicReference<>();
        respond("/issues", 201, "{\"number\":7}", null, body);

        Object created = client.post(base + "/issues", Map.of("title", "T"), "ghp_x", Object.class);

        assertThat(body.get()).isEqualTo("{\"title\":\"T\"}");
        assertThat(((Map<?, ?>) created).get("number")).isEqualTo(7);
    }

    @Test
    void patch_serialisesBody() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        respond("/issues/1", 200, "{\"number\":1}", null, body, method);

        Object updated = client.patch(base + "/issues/1", Map.of("title", "T"), "ghp_x", Object.class);

        assertThat(body.get()).isEqualTo("{\"title\":\"T\"}");
        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(((Map<?, ?>) updated).get("number")).isEqualTo(1);
    }

    @Test
    void unauthorized_mapsToPatHint() {
        respond("/user", 401, "{}", null, null);

        assertThatThrownBy(() -> client.get(base + "/user", "bad", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub authentication failed — check the Personal Access Token");
    }

    @Test
    void otherErrors_carryStatusAndBody() {
        respond("/user", 404, "{\"message\":\"Not Found\"}", null, null);

        assertThatThrownBy(() -> client.get(base + "/user", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub API error 404: {\"message\":\"Not Found\"}");
    }

    @Test
    void getRaw_exposesHeadersOfCheckedResponse() {
        respond("/user", 200, "{}", null, null);

        HttpResponse<String> response = client.getRaw(base + "/user", "t");

        assertThat(response.headers().firstValue("X-OAuth-Scopes")).contains("repo, read:org");
    }

    @Test
    void connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.get("http://127.0.0.1:1/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("GitHub API request failed: ");
    }
}
