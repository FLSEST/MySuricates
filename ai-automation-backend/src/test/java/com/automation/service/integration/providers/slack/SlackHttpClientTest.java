package com.automation.service.integration.providers.slack;

import com.automation.service.integration.providers.StubApiServer;
import com.automation.service.integration.providers.StubApiServer.Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transport and error mapping of the Slack Web API client against a local stub. */
class SlackHttpClientTest {

    private static final String TOKEN = "xoxb-1111-2222-abcdefSECRET";

    private StubApiServer stub;
    private SlackHttpClient client;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApiServer();
        client = new SlackHttpClient(new ObjectMapper(), stub.baseUrl() + "/api");
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void defaultBaseUrlIsTheWebApi() {
        assertThat(SlackHttpClient.DEFAULT_BASE_URL).isEqualTo("https://slack.com/api");
    }

    @Test
    void get_sendsBearerTokenAndEncodedQuery() {
        stub.onJson("/api/conversations.list", 200, "{\"ok\":true,\"channels\":[]}");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("types", "public_channel,private_channel");
        query.put("cursor", "dXNlcjpVMDYxTkZUVDI=");

        SlackHttpClient.SlackResponse response = client.get("conversations.list", query, TOKEN);

        assertThat(response.body()).containsEntry("ok", true);
        StubApiServer.Request req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.query()).isEqualTo("types=public_channel%2Cprivate_channel&cursor=dXNlcjpVMDYxTkZUVDI%3D");
        assertThat(req.header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void post_sendsJsonWithCharset() {
        stub.onJson("/api/chat.postMessage", 200, "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1503435956.000247\"}");

        client.post("chat.postMessage", Map.of("channel", "C123"), TOKEN);

        StubApiServer.Request req = stub.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.header("Content-Type")).isEqualTo("application/json; charset=utf-8");
        assertThat(req.body()).isEqualTo("{\"channel\":\"C123\"}");
    }

    @Test
    void scopesHeaderIsExposed() {
        stub.on("/api/auth.test", new Response(200, "{\"ok\":true,\"user\":\"bot\"}",
                Map.of("Content-Type", "application/json", "x-oauth-scopes", "chat:write,channels:read")));

        assertThat(client.get("auth.test", Map.of(), TOKEN).scopesHeader()).isEqualTo("chat:write,channels:read");
    }

    @Test
    void okFalse_withHttp200_isAnError() {
        stub.onJson("/api/chat.postMessage", 200, "{\"ok\":false,\"error\":\"channel_not_found\"}");

        assertThatThrownBy(() -> client.post("chat.postMessage", Map.of("channel", "C0"), TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Slack API error: channel_not_found");
    }

    @Test
    void okFalse_authErrors_mapToTokenHint() {
        stub.onJson("/api/auth.test", 200, "{\"ok\":false,\"error\":\"invalid_auth\"}");

        assertThatThrownBy(() -> client.get("auth.test", Map.of(), TOKEN))
                .hasMessage("Slack authentication failed (invalid_auth) — check the bot token");
    }

    @Test
    void okFalse_missingScope_namesTheNeededScope() {
        stub.onJson("/api/conversations.history", 200,
                "{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"channels:history\",\"provided\":\"chat:write\"}");

        assertThatThrownBy(() -> client.get("conversations.history", Map.of("channel", "C1"), TOKEN))
                .hasMessage("Slack API error: missing_scope (needed: channels:history)");
    }

    @Test
    void rateLimit_readsRetryAfterHeader() {
        stub.on("/api/conversations.history", new Response(429, "{\"ok\":false,\"error\":\"ratelimited\"}",
                Map.of("Retry-After", "30")));

        assertThatThrownBy(() -> client.get("conversations.history", Map.of(), TOKEN))
                .hasMessage("Slack rate limit reached (HTTP 429) — retry after 30 s");
    }

    @Test
    void serverError_carriesStatusOnly() {
        stub.on("/api/chat.postMessage", new Response(503, "<html>upstream</html>", Map.of()));

        assertThatThrownBy(() -> client.post("chat.postMessage", Map.of(), TOKEN))
                .hasMessage("Slack API error 503");
    }

    @Test
    void nonJsonOkBody_isRejected() {
        stub.on("/api/auth.test", new Response(200, "not json", Map.of()));

        assertThatThrownBy(() -> client.get("auth.test", Map.of(), TOKEN))
                .hasMessage("Slack API returned no valid JSON (HTTP 200)");
    }

    @Test
    void connectionFailure_isWrappedWithoutToken() {
        SlackHttpClient unreachable = new SlackHttpClient(new ObjectMapper(), "http://127.0.0.1:1/api");

        assertThatThrownBy(() -> unreachable.get("auth.test", Map.of(), TOKEN))
                .hasMessageStartingWith("Slack API request failed: ")
                .hasNoCause()
                .satisfies(e -> assertThat(e.toString()).doesNotContain(TOKEN));
    }
}
