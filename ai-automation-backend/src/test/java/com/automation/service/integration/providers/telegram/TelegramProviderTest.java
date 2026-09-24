package com.automation.service.integration.providers.telegram;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationVerification;
import com.automation.service.integration.providers.StubApiServer;
import com.automation.service.integration.providers.StubApiServer.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Telegram provider end to end against a local stub shaped after the Bot API reference
 * ({@code /bot<token>/<method>}, {@code {"ok":…,"result":…}} / {@code {"ok":false,"error_code":…,"description":…}}).
 * The bot token is part of the path, so every error path is also checked for token leaks.
 */
class TelegramProviderTest {

    private static final String TOKEN = "123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw";
    private static final String CREDENTIAL = "MeinBot|" + TOKEN;
    private static final String PATH = "/bot" + TOKEN;

    private final ObjectMapper mapper = new ObjectMapper();
    private StubApiServer stub;
    private TelegramProvider provider;

    @BeforeEach
    void start() throws Exception {
        stub = new StubApiServer();
        provider = new TelegramProvider(mapper, new TelegramHttpClient(mapper, stub.baseUrl()));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private JsonNode lastBody() throws Exception {
        return mapper.readTree(stub.lastRequest().body());
    }

    private static void assertNoToken(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN).doesNotContain("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw");
        }
        assertThat(e.toString()).doesNotContain(TOKEN);
    }

    @Test
    void defaultBaseUrl() {
        assertThat(TelegramHttpClient.DEFAULT_BASE_URL).isEqualTo("https://api.telegram.org");
    }

    @Test
    void metadata_directAuthAndSimulated() {
        assertThat(provider.getProviderId()).isEqualTo("telegram");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getVerification()).isEqualTo(IntegrationVerification.SIMULATED);
        assertThat(provider.getAvailableActions()).extracting(IntegrationAction::name)
                .containsExactly("send_message", "get_updates");
        IntegrationAction updates = provider.getAvailableActions().get(1);
        assertThat(updates.description()).contains("Webhook");
    }

    @Test
    void fetchAccountInfo_usesGetMe() throws Exception {
        stub.onJson(PATH + "/getMe", 200, "{\"ok\":true,\"result\":{\"id\":123456789,\"is_bot\":true,"
                + "\"first_name\":\"Suricate\",\"username\":\"suricate_bot\"}}");

        JsonNode info = mapper.readTree(provider.fetchAccountInfo(CREDENTIAL));

        assertThat(info.get("name").asText()).isEqualTo("Suricate");
        assertThat(info.get("username").asText()).isEqualTo("@suricate_bot");
        assertThat(stub.lastRequest().method()).isEqualTo("POST");
    }

    @Test
    void fetchAccountInfo_invalidToken_returnsNull() {
        stub.onJson(PATH + "/getMe", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

        assertThat(provider.fetchAccountInfo(CREDENTIAL)).isNull();
    }

    @Test
    void sendMessage_postsDocumentedFields() throws Exception {
        stub.onJson(PATH + "/sendMessage", 200, "{\"ok\":true,\"result\":{\"message_id\":42,\"chat\":{\"id\":-1001234567890}}}");

        Object result = provider.executeAction("send_message", Map.of("chat_id", "-1001234567890",
                "text", "*Build* grün", "parse_mode", "MarkdownV2", "disable_notification", "true"), CREDENTIAL);

        assertThat(((Map<?, ?>) result).get("message_id")).isEqualTo(42);
        assertThat(stub.lastRequest().header("Content-Type")).isEqualTo("application/json");
        JsonNode body = lastBody();
        assertThat(body.get("chat_id").isNumber()).isTrue();
        assertThat(body.get("chat_id").asLong()).isEqualTo(-1001234567890L);
        assertThat(body.get("text").asText()).isEqualTo("*Build* grün");
        assertThat(body.get("parse_mode").asText()).isEqualTo("MarkdownV2");
        assertThat(body.get("disable_notification").asBoolean()).isTrue();
    }

    @Test
    void sendMessage_acceptsChannelUsername() throws Exception {
        stub.onJson(PATH + "/sendMessage", 200, "{\"ok\":true,\"result\":{}}");

        provider.executeAction("send_message", Map.of("chat_id", "@mysuricates_news", "text", "Hallo"), CREDENTIAL);

        JsonNode body = lastBody();
        assertThat(body.get("chat_id").asText()).isEqualTo("@mysuricates_news");
        assertThat(body.has("parse_mode")).isFalse();
    }

    @Test
    void sendMessage_validation() {
        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("text", "x"), CREDENTIAL))
                .hasMessage("Required parameter missing: chat_id");
        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", "abc", "text", "x"), CREDENTIAL))
                .hasMessage("Parameter 'chat_id' must be a numeric chat id or @channelusername");
        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", " "), CREDENTIAL))
                .hasMessage("Required parameter missing: text");
        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x".repeat(4097)), CREDENTIAL))
                .hasMessage("Parameter 'text' is longer than 4096 characters");
        assertThatThrownBy(() -> provider.executeAction("send_message",
                Map.of("chat_id", 1, "text", "x", "parse_mode", "Markdown"), CREDENTIAL))
                .hasMessage("Parameter 'parse_mode' must be MarkdownV2 or HTML");
        assertThatThrownBy(() -> provider.executeAction("send_message",
                Map.of("chat_id", 1, "text", "x", "disable_notification", "ja"), CREDENTIAL))
                .hasMessage("Parameter 'disable_notification' must be true or false");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void getUpdates_shortPollsWithOffsetAndLimit() throws Exception {
        stub.onJson(PATH + "/getUpdates", 200, "{\"ok\":true,\"result\":[{\"update_id\":1001}]}");

        Object result = provider.executeAction("get_updates", Map.of("offset", "1001", "limit", 10), CREDENTIAL);

        assertThat(result).asList().hasSize(1);
        JsonNode body = lastBody();
        assertThat(body.get("offset").asLong()).isEqualTo(1001);
        assertThat(body.get("limit").asInt()).isEqualTo(10);
        assertThat(body.get("timeout").asInt()).isZero();
    }

    @Test
    void getUpdates_limitOutsideRange_isRejected() {
        assertThatThrownBy(() -> provider.executeAction("get_updates", Map.of("limit", 101), CREDENTIAL))
                .hasMessage("Parameter 'limit' must be between 1 and 100");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void getUpdates_whileWebhookActive_reportsConflict() {
        stub.onJson(PATH + "/getUpdates", 409, "{\"ok\":false,\"error_code\":409,\"description\":"
                + "\"Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first\"}");

        assertThatThrownBy(() -> provider.executeAction("get_updates", Map.of(), CREDENTIAL))
                .hasMessageStartingWith("Telegram API error 409: Conflict: can't use getUpdates method while webhook is active")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void okFalse_badRequest_usesDescription() {
        stub.onJson(PATH + "/sendMessage", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");

        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .hasMessage("Telegram API error 400: Bad Request: chat not found")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void unauthorized_mapsToTokenHintWithoutToken() {
        stub.onJson(PATH + "/sendMessage", 401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");

        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .hasMessage("Telegram authentication failed — check the bot token")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void rateLimit_readsRetryAfterFromParameters() {
        stub.onJson(PATH + "/sendMessage", 429, "{\"ok\":false,\"error_code\":429,"
                + "\"description\":\"Too Many Requests: retry after 17\",\"parameters\":{\"retry_after\":17}}");

        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .hasMessage("Telegram rate limit reached (HTTP 429) — retry after 17 s")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void serverError_withHtmlBody_carriesStatusOnly() {
        stub.on(PATH + "/sendMessage", new Response(502, "<html>Bad Gateway</html>", Map.of()));

        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .hasMessage("Telegram API error 502")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void descriptionEchoingTheToken_isScrubbed() {
        stub.onJson(PATH + "/sendMessage", 400, "{\"ok\":false,\"error_code\":400,\"description\":\"bad bot " + TOKEN + "\"}");

        assertThatThrownBy(() -> provider.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .hasMessage("Telegram API error 400: bad bot ***")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void connectionFailure_carriesNoTokenAndNoCause() {
        TelegramProvider offline = new TelegramProvider(mapper, new TelegramHttpClient(mapper, "http://127.0.0.1:1"));

        assertThatThrownBy(() -> offline.executeAction("send_message", Map.of("chat_id", 1, "text", "x"), CREDENTIAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("Telegram API request failed: ")
                .hasNoCause()
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void malformedToken_isRejectedBeforeAnyRequestWithoutEchoingIt() {
        String broken = "123:ab cd/../../evil";

        assertThatThrownBy(() -> provider.executeAction("get_updates", Map.of(), "x|" + broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Telegram bot token has an invalid format")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(broken).doesNotContain("evil"));
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void fetchAccountInfo_failureLogsNoToken() {
        // fetchAccountInfo logs e.getMessage(); the message must already be token-free.
        stub.onJson(PATH + "/getMe", 404, "{\"ok\":false,\"error_code\":404,\"description\":\"Not Found\"}");

        assertThat(provider.fetchAccountInfo(CREDENTIAL)).isNull();
        assertThatThrownBy(() -> new TelegramHttpClient(mapper, stub.baseUrl()).call(TOKEN, "getMe", Map.of()))
                .hasMessage("Telegram API error 404: Not Found")
                .satisfies(TelegramProviderTest::assertNoToken);
    }

    @Test
    void unknownAction_throws() {
        assertThatThrownBy(() -> provider.executeAction("ban_everyone", Map.of(), CREDENTIAL))
                .hasMessage("Unknown action: ban_everyone");
    }
}
