package com.automation.service.integration.providers.httpapi;

import com.automation.testsupport.TestTargetGuards;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpApiClient} against a loopback {@link HttpServer} — checks the wire behaviour (method,
 * body, headers, redirects, limits, redaction). Not a check against a real service.
 */
class HttpApiClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private HttpApiClient client;

    record Seen(String method, String path, String query, String auth, String contentType, String body) {}

    private final List<Seen> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/echo", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"), body));
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "application/json", "{\"method\":\"" + exchange.getRequestMethod()
                    + "\",\"echoedAuth\":\"" + auth + "\"}");
        });
        server.createContext("/api/text", exchange -> respond(exchange, 200, "text/plain", "hello"));
        server.createContext("/api/missing", exchange -> respond(exchange, 404, "application/json", "{\"error\":\"nope\"}"));
        server.createContext("/api/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://evil.example/steal");
            respond(exchange, 302, "text/plain", "");
        });
        server.createContext("/api/big", exchange -> respond(exchange, 200, "application/json",
                "[\"" + "x".repeat(HttpApiClient.MAX_RESPONSE_BYTES + 10) + "\"]"));
        server.createContext("/api/badjson", exchange -> respond(exchange, 200, "application/json", "{not json"));
        server.createContext("/elsewhere", exchange -> {
            seen.add("elsewhere");
            respond(exchange, 200, "text/plain", "outside");
        });
        server.start();
        client = new HttpApiClient(JSON, TestTargetGuards.outbound(TestTargetGuards.onWithLoopbackServerAsPublic()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpApiTarget target(String authHeader) {
        return HttpApiTarget.parse("http://127.0.0.1:" + server.getAddress().getPort() + "/api", authHeader);
    }

    private Map<String, Object> send(HttpApiTarget target, Map<String, Object> params) {
        return client.send(target, HttpApiRequest.from(target, params, JSON));
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Test
    void everyMethodReachesTheServer_withTheAuthHeader_andJsonIsParsed() {
        HttpApiTarget target = target("Authorization: Bearer s3cret-token");
        for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            Map<String, Object> result = send(target, Map.of("method", method, "path", "echo"));
            assertThat(result.get("status")).isEqualTo(200);
            assertThat(result.get("ok")).isEqualTo(true);
            assertThat(((JsonNode) result.get("body")).get("method").asText()).isEqualTo(method);
        }
        assertThat(requests).extracting(Seen::auth).containsOnly("Bearer s3cret-token");
        assertThat(requests).extracting(Seen::path).containsOnly("/api/echo");
    }

    @Test
    void theAuthValueIsScrubbedFromTheResult() {
        Map<String, Object> result = send(target("Authorization: Bearer s3cret-token"), Map.of("path", "echo"));

        assertThat(result.get("body").toString()).doesNotContain("s3cret-token").contains("\"echoedAuth\":\"***\"");
    }

    @Test
    void bodyAndQueryArrive() {
        send(target(""), Map.of("method", "POST", "path", "echo", "query", Map.of("q", "a b"),
                "body", Map.of("name", "x")));

        Seen request = requests.get(0);
        assertThat(request.query()).isEqualTo("q=a%20b");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo("{\"name\":\"x\"}");
        assertThat(request.auth()).isNull();
    }

    @Test
    void textComesBackAsText() {
        Map<String, Object> result = send(target(""), Map.of("path", "text"));

        assertThat(result.get("body")).isEqualTo("hello");
        assertThat(result.get("content_type")).isEqualTo("text/plain");
        assertThat(result.get("truncated")).isEqualTo(false);
    }

    @Test
    void errorStatusFailsByDefault_orIsReturnedWithFailOnErrorFalse() {
        HttpApiTarget target = target("");
        assertThatThrownBy(() -> send(target, Map.of("path", "missing")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 404").hasMessageContaining("nope");

        Map<String, Object> result = send(target, Map.of("path", "missing", "fail_on_error", false));
        assertThat(result.get("status")).isEqualTo(404);
        assertThat(result.get("ok")).isEqualTo(false);
    }

    @Test
    void redirectsAreNotFollowed() {
        Map<String, Object> result = send(target(""), Map.of("path", "redirect"));

        assertThat(result.get("status")).isEqualTo(302);
        assertThat(result.get("location")).isEqualTo("http://evil.example/steal");
        assertThat(result.get("note")).isEqualTo("redirects are not followed");
    }

    @Test
    void oversizedAnswersAreCutAndNotParsed() {
        Map<String, Object> result = send(target(""), Map.of("path", "big"));

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(result.get("body")).isInstanceOf(String.class);
        assertThat(((String) result.get("body")).length()).isEqualTo(HttpApiClient.MAX_TEXT_CHARS);
    }

    @Test
    void invalidJsonFallsBackToText() {
        assertThat(send(target(""), Map.of("path", "badjson")).get("body")).isEqualTo("{not json");
    }

    @Test
    void aPathOutsideTheBaseNeverReachesTheServer() {
        HttpApiTarget target = target("");
        assertThatThrownBy(() -> send(target, Map.of("path", "../elsewhere"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> send(target, Map.of("path", "%2e%2e/elsewhere"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void localTargetsAreReachableByDefault_andRefusedBeforeAnyRequestWithTheSwitchOn() {
        HttpApiTarget target = target("");
        HttpApiClient byDefault = new HttpApiClient(JSON, TestTargetGuards.outbound(TestTargetGuards.defaults()));
        assertThat(byDefault.send(target, HttpApiRequest.from(target, Map.of("path", "echo"), JSON)).get("status")).isEqualTo(200);
        assertThat(requests).hasSize(1);
        requests.clear();

        HttpApiClient guarded = new HttpApiClient(JSON, TestTargetGuards.outbound(TestTargetGuards.on()));

        assertThatThrownBy(() -> guarded.send(target, HttpApiRequest.from(target, Map.of("path", "echo"), JSON)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local or private network address")
                .hasMessageContaining("automation.hardening.private-network-guard-http-api=false");
        assertThat(requests).isEmpty();

        HttpApiClient open = new HttpApiClient(JSON, TestTargetGuards.outbound(TestTargetGuards.off()));
        assertThat(open.send(target, HttpApiRequest.from(target, Map.of("path", "echo"), JSON)).get("status")).isEqualTo(200);
        assertThat(requests).hasSize(1);
    }

    @Test
    void unreachableServiceFailsWithoutLeakingTheAuthValue() {
        HttpApiTarget target = HttpApiTarget.parse("http://127.0.0.1:1/api", "X-Api-Key: s3cret-token");

        assertThatThrownBy(() -> send(target, Map.of("path", "echo", "timeout_seconds", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http-api request failed")
                .hasMessageNotContaining("s3cret-token");
    }
}
