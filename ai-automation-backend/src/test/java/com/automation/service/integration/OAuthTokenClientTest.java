package com.automation.service.integration;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Revocation must follow RFC 7009 ({@code token=} in the form body, never a query string) and
 * stay silent on failure, and token errors must not carry the provider's response body into the
 * exception message — that message reaches the client as a 409 via GlobalExceptionHandler.
 */
class OAuthTokenClientTest {

    private static final String TOKEN_ERROR_BODY =
            "{\"error\":\"invalid_client\",\"error_description\":\"client_secret=s3cr3t is wrong\"}";
    /** A 200 that is not JSON at all — e.g. a captive portal or a reverse-proxy error page. */
    private static final String MALFORMED_OK_BODY = "<html><body>client_secret=s3cr3t</body></html>";

    private final OAuthTokenClient client = new OAuthTokenClient(new ObjectMapper());

    private HttpServer server;
    private final AtomicInteger revokeCalls = new AtomicInteger();
    private final AtomicInteger revokeStatus = new AtomicInteger(200);
    private final AtomicReference<String> revokeBody = new AtomicReference<>();
    private final AtomicReference<String> revokeMethod = new AtomicReference<>();
    private final AtomicReference<String> revokeContentType = new AtomicReference<>();
    private final AtomicReference<String> revokeQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/revoke", exchange -> {
            revokeCalls.incrementAndGet();
            revokeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            revokeMethod.set(exchange.getRequestMethod());
            revokeContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            revokeQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, revokeStatus.get(), "{}");
        });
        server.createContext("/token", exchange -> respond(exchange, 400, TOKEN_ERROR_BODY));
        server.createContext("/token-malformed", exchange -> respond(exchange, 200, MALFORMED_OK_BODY));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void revoke_sendsFormBody() {
        client.revoke("google-calendar", config(url("/revoke")), "access-token-1");

        assertThat(revokeCalls).hasValue(1);
        assertThat(revokeMethod).hasValue("POST");
        assertThat(revokeContentType).hasValue("application/x-www-form-urlencoded");
        assertThat(revokeBody).hasValue("token=access-token-1");
        assertThat(revokeQuery.get()).as("token must not leak into the query string").isNull();
    }

    @Test
    void revoke_nullUrl_noRequest() {
        // Atlassian 3LO has no documented revocation endpoint — skip instead of guessing one.
        client.revoke("jira", config(null), "access-token-1");

        assertThat(revokeCalls).hasValue(0);
    }

    @Test
    void revoke_serverError_doesNotThrow() {
        revokeStatus.set(500);

        assertThatCode(() -> client.revoke("google-calendar", config(url("/revoke")), "access-token-1"))
                .doesNotThrowAnyException();
        assertThat(revokeCalls).hasValue(1);
    }

    @Test
    void exchange_non200_messageHasStatusOnly() {
        assertThatThrownBy(() -> client.exchangeCodeForTokens(
                "google-calendar", config(null), "code-1", "client-id", "client-secret", "http://localhost/cb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token exchange failed for google-calendar (HTTP 400)");
    }

    @Test
    void refresh_non200_messageHasStatusOnly() {
        assertThatThrownBy(() -> client.refreshAccessToken(
                "google-calendar", config(null), "refresh-1", "client-id", "client-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token refresh failed for google-calendar (HTTP 400)");
    }

    @Test
    void exchange_malformed200Body_messageSaysMalformedAndHidesBody() {
        assertThatThrownBy(() -> client.exchangeCodeForTokens(
                "google-calendar", tokenConfig("/token-malformed"), "code-1", "client-id", "client-secret", "http://localhost/cb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token exchange failed for google-calendar (malformed response)")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("html");
    }

    @Test
    void refresh_malformed200Body_messageSaysMalformedAndHidesBody() {
        assertThatThrownBy(() -> client.refreshAccessToken(
                "google-calendar", tokenConfig("/token-malformed"), "refresh-1", "client-id", "client-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token refresh failed for google-calendar (malformed response)")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("html");
    }

    @Test
    void redactForLog_formEncoded_masksAllSensitiveKeysButKeepsOthers() {
        String body = "grant_type=authorization_code&code=auth-code-1&client_id=abc-123"
                + "&client_secret=super-secret&refresh_token=refresh-xyz&access_token=access-xyz";

        String redacted = OAuthTokenClient.redactForLog(body);

        assertThat(redacted)
                .contains("client_secret=***")
                .contains("refresh_token=***")
                .contains("access_token=***")
                .contains("code=***")
                .contains("client_id=abc-123")
                .doesNotContain("super-secret")
                .doesNotContain("refresh-xyz")
                .doesNotContain("access-xyz")
                .doesNotContain("auth-code-1");
    }

    @Test
    void redactForLog_json_masksAllSensitiveFieldsButKeepsOthers() {
        String body = "{\"error\":\"invalid_client\",\"client_secret\":\"super-secret\","
                + "\"refresh_token\":\"refresh-xyz\",\"access_token\":\"access-xyz\",\"code\":\"auth-code-1\"}";

        String redacted = OAuthTokenClient.redactForLog(body);

        assertThat(redacted)
                .contains("\"client_secret\":\"***\"")
                .contains("\"refresh_token\":\"***\"")
                .contains("\"access_token\":\"***\"")
                .contains("\"code\":\"***\"")
                .contains("\"error\":\"invalid_client\"")
                .doesNotContain("super-secret")
                .doesNotContain("refresh-xyz")
                .doesNotContain("access-xyz")
                .doesNotContain("auth-code-1");
    }

    @Test
    void redactForLog_doesNotMatchKeyNamesThatMerelyContainASensitiveWord() {
        String body = "postal_code=12345&authorization_code=abc&nickname=code-master";

        String redacted = OAuthTokenClient.redactForLog(body);

        assertThat(redacted).isEqualTo(body);
    }

    @Test
    void redactForLog_truncatesLongBodyTo300Characters() {
        String longBody = "x".repeat(500);

        String redacted = OAuthTokenClient.redactForLog(longBody);

        assertThat(redacted).hasSize(300);
    }

    @Test
    void redactForLog_null_returnsNull() {
        assertThat(OAuthTokenClient.redactForLog(null)).isNull();
    }

    private OAuthConfig config(String revocationUrl) {
        return tokenConfig("/token").withRevocationUrl(revocationUrl);
    }

    private OAuthConfig tokenConfig(String tokenPath) {
        return new OAuthConfig("https://auth.example/authorize", url(tokenPath),
                List.of("calendar.read"), "GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
