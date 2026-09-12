package com.automation.service.integration.providers.trello;

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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrelloHttpClientTest {

    private HttpServer server;
    private String base;
    private final TrelloHttpClient client = new TrelloHttpClient(new ObjectMapper());

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
                          AtomicReference<String> capturedBody, AtomicReference<String> capturedMethod) {
        server.createContext(path, ex -> {
            if (capturedAuth != null) capturedAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (capturedBody != null) capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (capturedMethod != null) capturedMethod.set(ex.getRequestMethod());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void get_noAuthorizationHeader() {
        AtomicReference<String> auth = new AtomicReference<>();
        respond("/members/me", 200, "{\"username\":\"flo\"}", auth, null, null);

        Object member = client.get(base + "/members/me?key=k&token=t", Object.class);

        assertThat(((Map<?, ?>) member).get("username")).isEqualTo("flo");
        assertThat(auth.get()).isNull();
    }

    @Test
    void post_sendsNoBody() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        respond("/cards", 200, "{\"id\":\"c1\"}", null, body, method);

        Object created = client.post(base + "/cards?key=k&token=t&name=Foo", Object.class);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEmpty();
        assertThat(((Map<?, ?>) created).get("id")).isEqualTo("c1");
    }

    @Test
    void put_sendsNoBody() {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        respond("/cards/c1", 200, "{\"id\":\"c1\"}", null, body, method);

        Object updated = client.put(base + "/cards/c1?key=k&token=t&name=Bar", Object.class);

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).isEmpty();
        assertThat(((Map<?, ?>) updated).get("id")).isEqualTo("c1");
    }

    @Test
    void delete_accepts200And204() {
        respond("/cards/c1", 204, "", null, null, null);
        respond("/cards/c2", 200, "", null, null, null);

        assertThatCode(() -> client.delete(base + "/cards/c1?key=k&token=t")).doesNotThrowAnyException();
        assertThatCode(() -> client.delete(base + "/cards/c2?key=k&token=t")).doesNotThrowAnyException();
    }

    // --- getRaw (used by TrelloProvider.fetchAccountInfo, which handles 401/non-200 itself) ---

    @Test
    void getRaw_returnsResponseWithoutMappingErrors() {
        respond("/members/me", 401, "{\"message\":\"invalid key\"}", null, null, null);

        var response = client.getRaw(base + "/members/me?key=k&token=t");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"message\":\"invalid key\"}");
    }

    @Test
    void otherErrors_carryStatusAndBody() {
        respond("/cards/c1", 404, "{\"message\":\"not found\"}", null, null, null);

        assertThatThrownBy(() -> client.get(base + "/cards/c1?key=k&token=t", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Trello API error 404: {\"message\":\"not found\"}");
    }

    @Test
    void connectionFailure_isWrapped() {
        assertThatThrownBy(() -> client.get("http://127.0.0.1:1/x", Object.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Trello API request failed: ");
    }
}
