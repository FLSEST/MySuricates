package com.automation.service.integration.providers.ntfy;

import com.automation.service.integration.IntegrationVerification;
import com.automation.testsupport.TestTargetGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider + HTTP client against a local stand-in for a ntfy server. */
class NtfyProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NtfyProvider provider = new NtfyProvider(objectMapper, new NtfyHttpClient(objectMapper, TestTargetGuards.loopbackOnly()));
    private HttpServer server;
    private String base;
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

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

    private void respond(String contextPath, int status, String response) {
        server.createContext(contextPath, ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            method.set(ex.getRequestMethod());
            path.set(ex.getRequestURI().getPath());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void publish_postsJsonToServerRootWithBearerToken() throws Exception {
        respond("/", 200, "{\"id\":\"abc\",\"event\":\"message\",\"topic\":\"alerts\"}");

        Object result = provider.executeAction("publish",
                Map.of("topic", "alerts", "message", "Run done", "priority", "max", "tags", "tada"),
                base + "|tk_secret");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/");
        assertThat(auth.get()).isEqualTo("Bearer tk_secret");
        Map<String, Object> sent = objectMapper.readValue(body.get(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(sent).containsEntry("topic", "alerts")
                .containsEntry("message", "Run done")
                .containsEntry("priority", 5)
                .containsEntry("tags", List.of("tada"));
        assertThat(((Map<?, ?>) result).get("id")).isEqualTo("abc");
    }

    @Test
    void publish_withoutTokenSendsNoAuthorizationHeader() {
        respond("/", 200, "{\"id\":\"x\"}");

        provider.executeAction("publish", Map.of("topic", "alerts", "message", "hi"), base + "|");

        assertThat(auth.get()).isNull();
    }

    @Test
    void publish_forbiddenMapsToTokenHint() {
        respond("/", 403, "{\"code\":40301,\"http\":403,\"error\":\"forbidden\"}");

        assertThatThrownBy(() -> provider.executeAction("publish", Map.of("topic", "alerts", "message", "hi"), base + "|tk_bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("access token");
    }

    @Test
    void publish_serverErrorCarriesStatusAndBody() {
        respond("/", 429, "{\"error\":\"limit reached\"}");

        assertThatThrownBy(() -> provider.executeAction("publish", Map.of("topic", "alerts", "message", "hi"), base + "|"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ntfy error 429")
                .hasMessageContaining("limit reached");
    }

    @Test
    void publish_invalidTopicFailsBeforeAnyRequest() {
        respond("/", 200, "{}");

        assertThatThrownBy(() -> provider.executeAction("publish", Map.of("topic", "a/b", "message", "hi"), base + "|"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(method.get()).isNull();
    }

    @Test
    void unknownActionIsRejected() {
        assertThatThrownBy(() -> provider.executeAction("subscribe", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchAccountInfo_withTokenReadsAccount() throws Exception {
        respond("/v1/account", 200, "{\"username\":\"alex\",\"role\":\"user\"}");

        String info = provider.fetchAccountInfo(base + "|tk_secret");

        assertThat(auth.get()).isEqualTo("Bearer tk_secret");
        Map<?, ?> parsed = objectMapper.readValue(info, Map.class);
        assertThat(parsed.get("username")).isEqualTo("alex");
        assertThat(parsed.get("name")).isEqualTo("alex @ 127.0.0.1");
        assertThat(parsed.get("server")).isEqualTo(base);
    }

    @Test
    void fetchAccountInfo_withoutTokenChecksHealth() throws Exception {
        respond("/v1/health", 200, "{\"healthy\":true}");

        String info = provider.fetchAccountInfo(base + "|");

        assertThat(path.get()).isEqualTo("/v1/health");
        assertThat(auth.get()).isNull();
        assertThat(objectMapper.readValue(info, Map.class).get("name")).isEqualTo("anonym @ 127.0.0.1");
    }

    @Test
    void fetchAccountInfo_returnsNullOnRejectedTokenOrBadUrl() {
        respond("/v1/account", 401, "{\"error\":\"unauthorized\"}");

        assertThat(provider.fetchAccountInfo(base + "|tk_bad")).isNull();
        assertThat(provider.fetchAccountInfo("ftp://example.org|")).isNull();
    }

    @Test
    void metadata() {
        assertThat(provider.getProviderId()).isEqualTo("ntfy");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.requiresCredentials()).isFalse();
        assertThat(provider.getAvailableActions()).extracting("name").containsExactly("publish");
        assertThat(provider.getVerification()).isIn(IntegrationVerification.VERIFIED, IntegrationVerification.SIMULATED);
    }
}
