package com.automation.service.integration.providers.httpapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpApiRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpApiTarget BASE = HttpApiTarget.parse("https://api.example.com/v1", "Authorization: Bearer t0ken");
    private static final HttpApiTarget ROOT = HttpApiTarget.parse("https://api.example.com", "");

    private static HttpApiRequest build(HttpApiTarget target, Map<String, Object> params) {
        return HttpApiRequest.from(target, params, JSON);
    }

    private static Map<String, Object> path(String path) {
        Map<String, Object> p = new HashMap<>();
        p.put("path", path);
        return p;
    }

    // ── path stays below the base URL ─────────────────────────────────────────

    @Test
    void relativePathIsResolvedBelowTheBasePath() {
        assertThat(build(BASE, path("users/42")).uri().toString()).isEqualTo("https://api.example.com/v1/users/42");
        assertThat(build(BASE, path("/users/42")).uri().toString()).isEqualTo("https://api.example.com/v1/users/42");
        assertThat(build(BASE, path("")).uri().toString()).isEqualTo("https://api.example.com/v1");
        assertThat(build(BASE, Map.of()).uri().toString()).isEqualTo("https://api.example.com/v1");
        assertThat(build(ROOT, path("status")).uri().toString()).isEqualTo("https://api.example.com/status");
        assertThat(build(ROOT, Map.of()).uri().toString()).isEqualTo("https://api.example.com/");
        assertThat(build(BASE, path("files/a%20b")).uri().getRawPath()).isEqualTo("/v1/files/a%20b");
        assertThat(build(BASE, path("/items/a:b")).uri().getRawPath()).isEqualTo("/v1/items/a:b");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/x", "http://evil.example", "HTTPS://evil.example", "//evil.example/x",
            "///evil.example", "javascript:alert(1)", "file:/etc/passwd", "mailto:x@y",
            "../admin", "a/../../admin", "..", ".", "a/./b", "a/..", "%2e%2e/admin", "%2E%2E", "a/%2e%2e/%2e%2e/x",
            ".%2e/x", "a%2fb", "a%2F..%2Fb", "a%5cb", "a\\b", "\\\\evil.example\\x",
            "a?x=1", "a#frag", "a b", "a\tb", "a\r\nHost: evil", "a%zz", "a%2"})
    void pathCannotEscapeTheBase(String escape) {
        assertThatThrownBy(() -> build(BASE, path(escape)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void pathMustBeAString() {
        assertThatThrownBy(() -> build(BASE, Map.of("path", Map.of("a", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── query ─────────────────────────────────────────────────────────────────

    @Test
    void queryIsEncodedAndListsRepeatTheName() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("q", "a b&c=d");
        query.put("n", 5);
        query.put("tag", List.of("x", "y"));
        query.put("skip", null);
        HttpApiRequest request = build(BASE, Map.of("path", "search", "query", query));

        assertThat(request.uri().getRawQuery()).isEqualTo("q=a%20b%26c%3Dd&n=5&tag=x&tag=y");
        assertThat(request.uri().getHost()).isEqualTo("api.example.com");
    }

    @Test
    void queryMustBeAFlatObject() {
        assertThatThrownBy(() -> build(BASE, Map.of("query", "a=1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(BASE, Map.of("query", Map.of("a", Map.of("b", 1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── method ────────────────────────────────────────────────────────────────

    @Test
    void methodDefaultsToGet_andAcceptsTheFiveVerbsCaseInsensitively() {
        assertThat(build(BASE, Map.of()).method()).isEqualTo("GET");
        for (String m : List.of("get", "Post", "PUT", "patch", "delete")) {
            assertThat(build(BASE, Map.of("method", m)).method()).isEqualTo(m.toUpperCase());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS", "TRACE", "CONNECT", "GET\r\nX", ""})
    void otherMethodsAreRejected(String method) {
        assertThatThrownBy(() -> build(BASE, Map.of("method", method))).isInstanceOf(IllegalArgumentException.class);
    }

    // ── headers ───────────────────────────────────────────────────────────────

    @Test
    void extraHeadersArePassedOn() {
        HttpApiRequest request = build(BASE, Map.of("headers", Map.of("Accept", "application/json", "X-Trace", 7)));

        assertThat(request.headers()).containsEntry("Accept", "application/json").containsEntry("X-Trace", "7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION"})
    void theAuthHeaderCannotBeOverridden(String name) {
        assertThatThrownBy(() -> build(BASE, Map.of("headers", Map.of(name, "Bearer other"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auth header");
    }

    @Test
    void withoutAuthOnTheConnection_anAuthorizationHeaderIsAllowed() {
        assertThat(build(ROOT, Map.of("headers", Map.of("Authorization", "Basic eA=="))).headers())
                .containsEntry("Authorization", "Basic eA==");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "content-length", "Transfer-Encoding", "Connection", "Upgrade", "Expect",
            "Bad Name", "X:Y", ""})
    void forbiddenOrInvalidHeaderNamesAreRejected(String name) {
        assertThatThrownBy(() -> build(BASE, Map.of("headers", Map.of(name, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\r\nX-Evil: 1", "a\nb", "a\rb", "a\u0000b", "a\u007fb"})
    void headerValuesWithLineBreaksOrControlCharactersAreRejected(String value) {
        assertThatThrownBy(() -> build(BASE, Map.of("headers", Map.of("X-Note", value))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line breaks");
    }

    // ── body ──────────────────────────────────────────────────────────────────

    @Test
    void structuredBodyIsSentAsJson() throws Exception {
        HttpApiRequest request = build(BASE, Map.of("method", "POST", "body", Map.of("name", "x", "n", 1)));

        assertThat(request.headers()).containsEntry("Content-Type", "application/json");
        assertThat(JSON.readTree(request.body()).get("name").asText()).isEqualTo("x");
    }

    @Test
    void textBodyIsSentAsPlainText_unlessAContentTypeIsGiven() {
        HttpApiRequest text = build(BASE, Map.of("method", "PUT", "body", "hällo"));
        assertThat(text.headers()).containsEntry("Content-Type", "text/plain; charset=utf-8");
        assertThat(new String(text.body(), StandardCharsets.UTF_8)).isEqualTo("hällo");

        HttpApiRequest xml = build(BASE, Map.of("method", "POST", "body", "<a/>",
                "headers", Map.of("content-type", "application/xml")));
        assertThat(xml.headers()).containsEntry("content-type", "application/xml").doesNotContainKey("Content-Type");
    }

    @Test
    void getWithBodyAndOversizedBodiesAreRejected() {
        assertThatThrownBy(() -> build(BASE, Map.of("body", "x"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET");
        String big = "x".repeat(HttpApiRequest.MAX_BODY_BYTES + 1);
        assertThatThrownBy(() -> build(BASE, Map.of("method", "POST", "body", big)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 MiB");
    }

    // ── timeout + fail_on_error ───────────────────────────────────────────────

    @Test
    void timeoutAndFailOnError() {
        assertThat(build(BASE, Map.of()).timeout().toSeconds()).isEqualTo(30);
        assertThat(build(BASE, Map.of("timeout_seconds", "5")).timeout().toSeconds()).isEqualTo(5);
        assertThatThrownBy(() -> build(BASE, Map.of("timeout_seconds", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(BASE, Map.of("timeout_seconds", 121))).isInstanceOf(IllegalArgumentException.class);
        assertThat(build(BASE, Map.of()).failOnError()).isTrue();
        assertThat(build(BASE, Map.of("fail_on_error", "false")).failOnError()).isFalse();
        assertThatThrownBy(() -> build(BASE, Map.of("fail_on_error", "maybe"))).isInstanceOf(IllegalArgumentException.class);
    }

    /** ac639b39: timeout_seconds 1.5 used to become 1 and 2^32 + 5 became 5 — read like its text; blank is the default. */
    @Test
    void timeoutIsReadLikeItsText() {
        assertThatThrownBy(() -> build(BASE, Map.of("timeout_seconds", 1.5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("timeout_seconds must be a number");
        assertThatThrownBy(() -> build(BASE, Map.of("timeout_seconds", 4_294_967_301L)))
                .hasMessage("timeout_seconds must be a number");
        assertThat(build(BASE, Map.of("timeout_seconds", " ")).timeout().toSeconds()).isEqualTo(30);
    }
}
