package com.automation.service.integration.providers.feed;

import com.automation.testsupport.TestTargetGuards;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Provider + fetcher against a local HTTP server. */
class FeedProviderTest {

    private HttpServer server;
    private String base;
    private final FeedProvider provider = new FeedProvider(new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.onWithLoopbackServerAsPublic())));

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(Map<String, Object> params) {
        return (Map<String, Object>) provider.executeAction("fetch_feed", params, null);
    }

    @Test
    void fetchFeed_returnsNewestFirstWithIsoDates() {
        serve("/rss", 200, FeedParserTest.RSS, null);

        Map<String, Object> result = fetch(Map.of("url", base + "/rss"));

        assertThat(result.get("count")).isEqualTo(3);
        assertThat(result.get("totalInFeed")).isEqualTo(3);
        assertThat((Map<String, Object>) result.get("feed")).containsEntry("format", "rss").containsEntry("title", "Beispiel-Blog");
        List<Map<String, Object>> items = items(result);
        assertThat(items).extracting(i -> i.get("title"))
                .containsExactly("Neuer Beitrag", "Erster & alter Beitrag", "Ohne Datum");
        assertThat(items.get(0).get("published")).isEqualTo("2026-09-20T10:00:00Z");
        assertThat(items.get(2).get("published")).isNull();
    }

    @Test
    void fetchFeed_sinceDropsOlderAndUndatedEntries() {
        serve("/rss", 200, FeedParserTest.RSS, null);

        Map<String, Object> result = fetch(Map.of("url", base + "/rss", "since", "2026-09-10"));

        assertThat(items(result)).extracting(i -> i.get("title")).containsExactly("Neuer Beitrag");
        assertThat(result.get("totalInFeed")).isEqualTo(3);
    }

    @Test
    void fetchFeed_limitCutsAfterSorting() {
        serve("/atom", 200, FeedParserTest.ATOM, null);

        Map<String, Object> result = fetch(Map.of("url", base + "/atom", "limit", "1"));

        assertThat(items(result)).extracting(i -> i.get("title")).containsExactly("v1.1 beta");
    }

    @Test
    void fetchFeed_followsRelativeRedirect() {
        serve("/old", 301, "", "/new");
        serve("/new", 200, FeedParserTest.RDF, null);

        assertThat(fetch(Map.of("url", base + "/old")).get("count")).isEqualTo(1);
    }

    @Test
    void fetchFeed_stopsRedirectLoops() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/loop", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });

        assertThatThrownBy(() -> fetch(Map.of("url", base + "/loop")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirects");
        assertThat(hits.get()).isEqualTo(FeedFetcher.MAX_REDIRECTS + 1);
    }

    @Test
    void fetchFeed_refusesRedirectToAnInternalAddress_beforeConnecting() {
        serve("/meta", 302, "", "http://169.254.169.254/latest/meta-data/");

        assertThatThrownBy(() -> fetch(Map.of("url", base + "/meta")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("169.254.169.254")
                .hasMessageContaining("private-network-guard");
    }

    @Test
    void fetchFeed_refusesLocalTargetsByDefault_andReachesThemWithTheSwitchOff() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/local", ex -> {
            hits.incrementAndGet();
            byte[] bytes = FeedParserTest.RSS.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });

        assertThatThrownBy(() -> new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.defaults())).fetch(base + "/local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local or private network address");
        assertThat(hits).hasValue(0);

        assertThat(new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.off())).fetch(base + "/local")).isNotEmpty();
        assertThat(hits).hasValue(1);
    }

    @Test
    void fetchFeed_rejectsRedirectToFileScheme() {
        serve("/evil", 302, "", "file:///etc/passwd");

        assertThatThrownBy(() -> fetch(Map.of("url", base + "/evil")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
    }

    @Test
    void fetchFeed_httpErrorCarriesStatus() {
        serve("/gone", 404, "not here", null);

        assertThatThrownBy(() -> fetch(Map.of("url", base + "/gone")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void fetcher_enforcesSizeLimit() {
        serve("/big", 200, FeedParserTest.RSS, null);
        FeedFetcher small = new FeedFetcher(TestTargetGuards.outbound(TestTargetGuards.off()), 100);

        assertThatThrownBy(() -> small.fetch(base + "/big"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("larger than");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.org/feed", "jar:file:/x.jar!/a", "/relative", "http://"})
    void fetchFeed_rejectsNonHttpUrls(String url) {
        assertThatThrownBy(() -> fetch(Map.of("url", url))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void params_validation() {
        assertThatThrownBy(() -> fetch(Map.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("url");
        assertThatThrownBy(() -> FeedProvider.since("letzte Woche")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedProvider.limit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedProvider.limit("viele")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FeedProvider.limit(null)).isEqualTo(FeedProvider.DEFAULT_LIMIT);
        assertThat(FeedProvider.limit(10_000)).isEqualTo(FeedProvider.MAX_LIMIT);
        assertThatThrownBy(() -> provider.executeAction("other", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** ac639b39: limit 1.5 used to become 1 and 3000000000 wrapped around — read like its text now. */
    @Test
    void limit_isReadLikeItsText() {
        assertThat(FeedProvider.limit(" 3 ")).isEqualTo(3);
        assertThatThrownBy(() -> FeedProvider.limit(1.5)).hasMessage("Parameter 'limit' must be a number: 1.5");
        assertThatThrownBy(() -> FeedProvider.limit(3_000_000_000L))
                .hasMessage("Parameter 'limit' must be a number: 3000000000");
    }

    @Test
    void metadata() {
        assertThat(provider.getProviderId()).isEqualTo("feed");
        assertThat(provider.requiresCredentials()).isFalse();
        assertThat(provider.requiresOAuthFlow()).isFalse();
        // Account name without language as in the iCal feed – the UI shows it unchanged, even in English (6e1e87c2)
        assertThat(provider.fetchAccountInfo("|")).isEqualTo("{\"name\":\"RSS/Atom\"}");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }
}
