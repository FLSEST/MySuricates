package com.automation.service.integration.providers.gitlab;

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

class GitLabHttpClientTest {

    private HttpServer server;
    private String base;
    private final GitLabHttpClient client = new GitLabHttpClient(new ObjectMapper(), TestTargetGuards.loopbackOnly());
    private final AtomicReference<String> privateToken = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> rawPath = new AtomicReference<>();

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
            privateToken.set(ex.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            method.set(ex.getRequestMethod());
            rawPath.set(ex.getRequestURI().getRawPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void get_sendsPrivateTokenHeaderOnly() {
        respond("/api/v4/user", 200, "{\"username\":\"alex\"}");

        Object user = client.get(base + "/api/v4/user", "glpat-x", Object.class);

        assertThat(((Map<?, ?>) user).get("username")).isEqualTo("alex");
        assertThat(privateToken.get()).isEqualTo("glpat-x");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void encodedProjectPath_staysOneSegment() {
        respond("/api/v4/projects/", 200, "[]");

        client.get(base + "/api/v4/projects/grp%2Fsub%2Fapp/issues", "t", Object.class);

        assertThat(rawPath.get()).isEqualTo("/api/v4/projects/grp%2Fsub%2Fapp/issues");
    }

    @Test
    void post_and_put_serialiseBodyWithTheirMethod() {
        respond("/issues", 201, "{\"iid\":3}");

        client.post(base + "/issues", Map.of("title", "T"), "t", Object.class);
        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEqualTo("{\"title\":\"T\"}");

        client.put(base + "/issues", Map.of("state_event", "close"), "t", Object.class);
        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).isEqualTo("{\"state_event\":\"close\"}");
    }

    @Test
    void unauthorized_mapsToTokenHint() {
        respond("/user", 401, "{\"message\":\"401 Unauthorized\"}");

        assertThatThrownBy(() -> client.get(base + "/user", "secret", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitLab authentication failed — check the access token");
    }

    @Test
    void forbidden_mapsToScopeHint() {
        respond("/x", 403, "{\"error\":\"insufficient_scope\"}");

        assertThatThrownBy(() -> client.post(base + "/x", Map.of(), "t", Object.class))
                .hasMessageStartingWith("GitLab denied the request (403)")
                .hasMessageContaining("'api' scope")
                .hasMessageContaining("insufficient_scope");
    }

    @Test
    void notFoundAndServerErrors_carryStatusAndShortenedBody() {
        respond("/nf", 404, "{\"message\":\"404 Project Not Found\"}");
        respond("/boom", 502, "x".repeat(3000));

        assertThatThrownBy(() -> client.get(base + "/nf", "t", Object.class))
                .hasMessage("GitLab API error 404: {\"message\":\"404 Project Not Found\"}");
        assertThatThrownBy(() -> client.get(base + "/boom", "t", Object.class))
                .hasMessageStartingWith("GitLab API error 502: xxx")
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(600));
    }

    @Test
    void connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.get("http://127.0.0.1:1/x", "t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("GitLab API request failed: ");
    }
}
