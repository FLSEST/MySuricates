package com.automation.service.integration.providers.slack;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationVerification;
import com.automation.service.integration.providers.StubApiServer;
import com.automation.service.integration.providers.StubApiServer.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slack provider end to end against a local stub shaped after the Web API reference
 * (chat.postMessage, conversations.list/history, auth.test; {@code ok:false} errors with HTTP 200).
 */
class SlackProviderTest {

    private static final String TOKEN = "xoxb-test-token";
    private static final String CREDENTIAL = "Team-Workspace|" + TOKEN;

    private final ObjectMapper mapper = new ObjectMapper();
    private StubApiServer stub;
    private SlackProvider provider;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApiServer();
        provider = new SlackProvider(mapper, new SlackHttpClient(mapper, stub.baseUrl() + "/api"));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private JsonNode lastBody() throws Exception {
        return mapper.readTree(stub.lastRequest().body());
    }

    @Test
    void metadata_directAuthAndSimulated() {
        assertThat(provider.getProviderId()).isEqualTo("slack");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getVerification()).isEqualTo(IntegrationVerification.SIMULATED);
        assertThat(provider.getAvailableActions()).extracting(IntegrationAction::name)
                .containsExactly("post_message", "list_channels", "get_channel_history");
    }

    @Test
    void actionsDeclareTheBotScopesTheyNeed() {
        Map<String, List<String>> scopes = new java.util.HashMap<>();
        provider.getAvailableActions().forEach(a -> scopes.put(a.name(), a.requiredScopes()));
        assertThat(scopes).containsEntry("post_message", List.of("chat:write"))
                .containsEntry("list_channels", List.of("channels:read"))
                .containsEntry("get_channel_history", List.of("channels:history"));
    }

    @Test
    void fetchAccountInfo_usesAuthTest() throws Exception {
        stub.onJson("/api/auth.test", 200, "{\"ok\":true,\"url\":\"https://acme.slack.com/\",\"team\":\"Acme\","
                + "\"user\":\"mysuricates\",\"team_id\":\"T12345\",\"user_id\":\"U12345\",\"bot_id\":\"B12345\"}");

        JsonNode info = mapper.readTree(provider.fetchAccountInfo(CREDENTIAL));

        assertThat(info.get("name").asText()).isEqualTo("mysuricates");
        assertThat(info.get("team").asText()).isEqualTo("Acme");
        assertThat(stub.lastRequest().header("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void fetchAccountInfo_invalidToken_returnsNull() {
        stub.onJson("/api/auth.test", 200, "{\"ok\":false,\"error\":\"invalid_auth\"}");

        assertThat(provider.fetchAccountInfo(CREDENTIAL)).isNull();
    }

    @Test
    void fetchGrantedScopes_readsHeaderSpaceSeparated() {
        stub.on("/api/auth.test", new Response(200, "{\"ok\":true}",
                Map.of("x-oauth-scopes", "chat:write,channels:read, channels:history")));

        assertThat(provider.fetchGrantedScopes(CREDENTIAL)).isEqualTo("chat:write channels:read channels:history");
    }

    @Test
    void fetchGrantedScopes_withoutHeader_isUnknown() {
        stub.onJson("/api/auth.test", 200, "{\"ok\":true}");

        assertThat(provider.fetchGrantedScopes(CREDENTIAL)).isNull();
    }

    @Test
    void postMessage_postsChannelTextAndThread() throws Exception {
        stub.onJson("/api/chat.postMessage", 200, "{\"ok\":true,\"channel\":\"C123ABC456\",\"ts\":\"1503435956.000247\"}");

        Object result = provider.executeAction("post_message", Map.of("channel", "C123ABC456",
                "text", "Build *grün*", "thread_ts", "1503435956.000247"), CREDENTIAL);

        assertThat(((Map<?, ?>) result).get("ts")).isEqualTo("1503435956.000247");
        JsonNode body = lastBody();
        assertThat(body.get("channel").asText()).isEqualTo("C123ABC456");
        assertThat(body.get("text").asText()).isEqualTo("Build *grün*");
        assertThat(body.get("thread_ts").asText()).isEqualTo("1503435956.000247");
    }

    @Test
    void postMessage_acceptsBlocksAsJsonText() throws Exception {
        stub.onJson("/api/chat.postMessage", 200, "{\"ok\":true}");
        String blocks = "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"Hallo\"}}]";

        provider.executeAction("post_message", Map.of("channel", "C1", "text", "Hallo", "blocks", blocks), CREDENTIAL);

        JsonNode sent = lastBody().get("blocks");
        assertThat(sent.isArray()).isTrue();
        assertThat(sent.get(0).get("type").asText()).isEqualTo("section");
    }

    @Test
    void postMessage_validation() {
        assertThatThrownBy(() -> provider.executeAction("post_message", Map.of("text", "x"), CREDENTIAL))
                .hasMessage("Required parameter missing: channel");
        assertThatThrownBy(() -> provider.executeAction("post_message", Map.of("channel", "C1"), CREDENTIAL))
                .hasMessage("Parameter 'text' or 'blocks' is required");
        assertThatThrownBy(() -> provider.executeAction("post_message",
                Map.of("channel", "C1", "blocks", "{kaputt"), CREDENTIAL))
                .hasMessage("Parameter 'blocks' must be a JSON array of Block Kit blocks");
        assertThatThrownBy(() -> provider.executeAction("post_message",
                Map.of("channel", "C1", "text", "x", "thread_ts", "gestern"), CREDENTIAL))
                .hasMessageStartingWith("Parameter 'thread_ts' must be a Slack timestamp");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void postMessage_okFalse_surfacesSlackErrorWithoutToken() {
        stub.onJson("/api/chat.postMessage", 200, "{\"ok\":false,\"error\":\"not_in_channel\"}");

        assertThatThrownBy(() -> provider.executeAction("post_message", Map.of("channel", "C1", "text", "x"), CREDENTIAL))
                .hasMessage("Slack API error: not_in_channel")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void listChannels_usesConversationsListWithCursor() {
        stub.onJson("/api/conversations.list", 200, "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],"
                + "\"response_metadata\":{\"next_cursor\":\"dGVhbTpDMDYxRkE1UEI=\"}}");

        Object result = provider.executeAction("list_channels", Map.of("limit", 200, "cursor", "abc="), CREDENTIAL);

        assertThat(((Map<?, ?>) result).get("channels")).asList().hasSize(1);
        assertThat(stub.lastRequest().method()).isEqualTo("GET");
        assertThat(stub.lastRequest().query())
                .isEqualTo("types=public_channel&exclude_archived=true&limit=200&cursor=abc%3D");
    }

    /** ac639b39: limit 1.5 used to become 1 and 2^32 + 1 as well — read like its text, both fail before any request. */
    @Test
    void limit_isReadLikeItsText() {
        assertThatThrownBy(() -> provider.executeAction("list_channels", Map.of("limit", 1.5), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be a number");
        assertThatThrownBy(() -> provider.executeAction("list_channels", Map.of("limit", 4_294_967_297L), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be a number");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void listChannels_validatesTypesAndLimit() {
        assertThatThrownBy(() -> provider.executeAction("list_channels", Map.of("types", "public_channel,dm"), CREDENTIAL))
                .hasMessage("Parameter 'types' allows only public_channel, private_channel, mpim, im");
        assertThatThrownBy(() -> provider.executeAction("list_channels", Map.of("limit", 1001), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be between 1 and 1000");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void channelHistory_passesDocumentedArguments() {
        stub.onJson("/api/conversations.history", 200, "{\"ok\":true,\"messages\":[],\"has_more\":false}");

        provider.executeAction("get_channel_history", Map.of("channel", "C123", "limit", "15",
                "oldest", "1503435956", "latest", "1503435999.000100"), CREDENTIAL);

        assertThat(stub.lastRequest().query())
                .isEqualTo("channel=C123&limit=15&oldest=1503435956&latest=1503435999.000100");
    }

    @Test
    void channelHistory_validation() {
        assertThatThrownBy(() -> provider.executeAction("get_channel_history", Map.of(), CREDENTIAL))
                .hasMessage("Required parameter missing: channel");
        assertThatThrownBy(() -> provider.executeAction("get_channel_history", Map.of("channel", "C1", "limit", 1000), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be between 1 and 999");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void rateLimited_surfacesRetryAfter() {
        stub.on("/api/conversations.history", new Response(429, "", Map.of("Retry-After", "60")));

        assertThatThrownBy(() -> provider.executeAction("get_channel_history", Map.of("channel", "C1"), CREDENTIAL))
                .hasMessage("Slack rate limit reached (HTTP 429) — retry after 60 s");
    }

    @Test
    void unknownAction_throws() {
        assertThatThrownBy(() -> provider.executeAction("delete_channel", Map.of(), CREDENTIAL))
                .hasMessage("Unknown action: delete_channel");
    }
}
