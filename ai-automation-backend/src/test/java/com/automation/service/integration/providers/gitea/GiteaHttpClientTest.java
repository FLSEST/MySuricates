package com.automation.service.integration.providers.gitea;

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

class GiteaHttpClientTest {

    private HttpServer server;
    private String base;
    private final GiteaHttpClient client = new GiteaHttpClient(new ObjectMapper());
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();

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

    private void respond(String path, int status, String responseBody) {
        server.createContext(path, ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            method.set(ex.getRequestMethod());
            query.set(ex.getRequestURI().getRawQuery());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void get_sendsTokenHeaderAndParsesJson() {
        respond("/api/v1/user", 200, "{\"login\":\"flo\"}");

        Object user = client.get(base + "/api/v1/user?x=1", "tok", Object.class);

        assertThat(((Map<?, ?>) user).get("login")).isEqualTo("flo");
        assertThat(auth.get()).isEqualTo("token tok");
        assertThat(method.get()).isEqualTo("GET");
        assertThat(query.get()).isEqualTo("x=1");
    }

    @Test
    void post_patch_put_serialiseBodyWithTheirMethod() {
        respond("/issues", 201, "{\"number\":7}");

        client.post(base + "/issues", Map.of("title", "T"), "tok", Object.class);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEqualTo("{\"title\":\"T\"}");

        client.patch(base + "/issues", Map.of("state", "closed"), "tok", Object.class);
        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(body.get()).isEqualTo("{\"state\":\"closed\"}");

        client.put(base + "/issues", Map.of("labels", java.util.List.of(1, 2)), "tok", Object.class);
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).isEqualTo("{\"labels\":[1,2]}");
    }

    @Test
    void emptyResponseBody_isNull() {
        respond("/empty", 204, "");

        assertThat(client.put(base + "/empty", Map.of(), "tok", Object.class)).isNull();
    }

    @Test
    void unauthorized_mapsToTokenHintWithoutToken() {
        respond("/user", 401, "{\"message\":\"token is required\"}");

        assertThatThrownBy(() -> client.get(base + "/user", "secret-tok", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Gitea authentication failed — check the access token");
    }

    @Test
    void notFound_carriesStatusAndBody() {
        respond("/x", 404, "{\"message\":\"not found\"}");

        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .hasMessage("Gitea API error 404: {\"message\":\"not found\"}");
    }

    @Test
    void serverError_bodyIsShortened() {
        respond("/x", 500, "e".repeat(2000));

        assertThatThrownBy(() -> client.get(base + "/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Gitea API error 500: eee")
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(600));
    }

    @Test
    void connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.get("http://127.0.0.1:1/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Gitea API request failed: ");
    }
}
