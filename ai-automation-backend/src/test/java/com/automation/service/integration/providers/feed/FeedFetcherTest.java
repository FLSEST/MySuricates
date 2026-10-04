package com.automation.service.integration.providers.feed;

import com.automation.testsupport.TestTargetGuards;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedFetcherTest {

    private HttpServer server;
    private String base;
    private final FeedFetcher fetcher = new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.onWithLoopbackServerAsPublic()));

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

    private void serve(String path, int status, String body, String location) {
        server.createContext(path, ex -> {
            if (location != null) ex.getResponseHeaders().add("Location", location);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) ex.getResponseBody().write(bytes);
            ex.close();
        });
    }

    @Test
    void fetch_returnsBody_andSendsFeedAcceptAndUserAgent() {
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        server.createContext("/feed", ex -> {
            accept.set(ex.getRequestHeaders().getFirst("Accept"));
            agent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            byte[] b = "<rss/>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });

        assertThat(new String(fetcher.fetch(base + "/feed"), StandardCharsets.UTF_8)).isEqualTo("<rss/>");
        assertThat(accept.get()).contains("application/rss+xml", "application/atom+xml");
        assertThat(agent.get()).startsWith("MySuricates-FeedReader");
    }

    @Test
    void fetch_followsRelativeRedirect() {
        serve("/old", 301, "", "/new");
        serve("/new", 200, "neu", null);
        assertThat(new String(fetcher.fetch(base + "/old"), StandardCharsets.UTF_8)).isEqualTo("neu");
    }

    @Test
    void fetch_redirectWithoutLocation_fails() {
        serve("/loc", 302, "", null);
        assertThatThrownBy(() -> fetcher.fetch(base + "/loc"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without Location");
    }

    @Test
    void fetch_redirectToNonHttpScheme_isRejected() {
        serve("/bad", 302, "", "file:///etc/passwd");
        assertThatThrownBy(() -> fetcher.fetch(base + "/bad"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http or https");
    }

    @Test
    void redirectTarget_neverDowngradesFromHttpsToHttp() {
        URI secure = URI.create("https://feeds.example/rss?token=geheim");
        assertThatThrownBy(() -> FeedFetcher.redirectTarget(secure, "http://feeds.example/rss"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("from https to http")
                .hasMessageNotContaining("geheim");
        assertThat(FeedFetcher.redirectTarget(secure, "/neu").toString()).isEqualTo("https://feeds.example/neu");
        assertThat(FeedFetcher.redirectTarget(URI.create("http://feeds.example/a"), "https://cdn.example/b").getScheme())
                .isEqualTo("https");
        assertThat(FeedFetcher.redirectTarget(URI.create("http://feeds.example/a"), "http://other.example/b").getHost())
                .isEqualTo("other.example");
    }

    @Test
    void fetch_endlessRedirectStopsAfterMaxHops() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/loop", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        assertThatThrownBy(() -> fetcher.fetch(base + "/loop"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("redirects");
        assertThat(hits.get()).isEqualTo(FeedFetcher.MAX_REDIRECTS + 1);
    }

    @Test
    void fetch_httpErrorNamesStatusAndUrl() {
        serve("/gone", 404, "nein", null);
        assertThatThrownBy(() -> fetcher.fetch(base + "/gone"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 404").hasMessageContaining("/gone");
    }

    @Test
    void fetch_sizeCap_appliesToDeclaredLengthAndToActualBytes() {
        FeedFetcher small = new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.off()), 100);
        serve("/big", 200, "x".repeat(101), null);
        assertThatThrownBy(() -> small.fetch(base + "/big")).isInstanceOf(IllegalStateException.class).hasMessageContaining("larger than");

        // chunked: no Content-Length, so only the read limit can catch it
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("y".repeat(150).getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        assertThatThrownBy(() -> small.fetch(base + "/chunked")).isInstanceOf(IllegalStateException.class).hasMessageContaining("larger than");

        serve("/exact", 200, "z".repeat(100), null);
        assertThat(small.fetch(base + "/exact")).hasSize(100);
    }

    @Test
    void fetch_guardBlocksPrivateTarget_beforeAnyRequest() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/x", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        assertThatThrownBy(() -> new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.on())).fetch(base + "/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void fetch_connectionFailureIsWrapped() {
        server.stop(0);
        assertThatThrownBy(() -> new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.off())).fetch(base + "/x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Feed request failed");
    }

    @Test
    void requireHttpUri_acceptsHttpAndHttps_trimsInput() {
        assertThat(FeedFetcher.requireHttpUri("  HTTPS://example.org/feed ").getHost()).isEqualTo("example.org");
        assertThat(FeedFetcher.requireHttpUri("http://example.org").getScheme()).isEqualTo("http");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.org/f", "/relative/feed", "example.org/feed", "http:///nohost"})
    void requireHttpUri_rejectsOtherSchemesRelativeAndHostless(String url) {
        assertThatThrownBy(() -> FeedFetcher.requireHttpUri(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireHttpUri_blankAndMalformed() {
        assertThatThrownBy(() -> FeedFetcher.requireHttpUri(null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("url");
        assertThatThrownBy(() -> FeedFetcher.requireHttpUri("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedFetcher.requireHttpUri("http://exa mple.org")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid feed URL");
    }
}
