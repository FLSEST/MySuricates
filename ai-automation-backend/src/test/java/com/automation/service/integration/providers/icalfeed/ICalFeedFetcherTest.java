package com.automation.service.integration.providers.icalfeed;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ICalFeedFetcherTest {

    private HttpServer server;
    private String base;
    private final ICalFeedFetcher fetcher = new ICalFeedFetcher();

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

    private void serve(String path, int status, byte[] body, boolean chunked) {
        server.createContext(path, ex -> {
            ex.sendResponseHeaders(status, chunked ? 0 : (body.length == 0 ? -1 : body.length));
            if (body.length > 0) ex.getResponseBody().write(body);
            ex.close();
        });
    }

    @Test
    void fetch_returnsTheCalendarText() {
        serve("/ok", 200, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8), false);
        assertThat(fetcher.fetch(URI.create(base + "/ok"))).startsWith("BEGIN:VCALENDAR");
    }

    @Test
    void fetch_httpErrorsNameTheHostButNotThePath() {
        serve("/secret-token-123/basic.ics", 404, new byte[0], false);
        assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/secret-token-123/basic.ics")))
                .hasMessage("iCal feed at 127.0.0.1 answered HTTP 404")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void fetch_rejectsNonCalendarBodies() {
        serve("/html", 200, "<html>Login</html>".getBytes(StandardCharsets.UTF_8), false);
        assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/html"))).hasMessageContaining("not an iCalendar feed");
    }

    @Test
    void fetch_rejectsOversizedFeeds_announcedOrStreamed() {
        byte[] big = new byte[(int) ICalFeedFetcher.MAX_BYTES + 1];
        serve("/big", 200, big, false);
        serve("/big-chunked", 200, big, true);
        assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/big"))).hasMessageContaining("size limit of 5 MB");
        assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/big-chunked"))).hasMessageContaining("size limit of 5 MB");
    }

    @Test
    void fetch_connectionFailureIsWrapped() {
        assertThatThrownBy(() -> fetcher.fetch(URI.create("http://127.0.0.1:1/x.ics")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("iCal feed at 127.0.0.1 could not be loaded");
    }
}
