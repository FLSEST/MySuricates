package com.automation.service.integration.providers.icalfeed;

import com.automation.service.integration.IntegrationAction;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("unchecked")
class ICalFeedProviderTest {

    private static final String FEED = String.join("\r\n",
            "BEGIN:VCALENDAR", "VERSION:2.0", "X-WR-CALNAME:Feiertage Test",
            "BEGIN:VEVENT", "UID:h1", "DTSTART;VALUE=DATE:20261003", "DTEND;VALUE=DATE:20261004", "SUMMARY:Einheit", "END:VEVENT",
            "BEGIN:VEVENT", "UID:h0", "DTSTART;VALUE=DATE:20260101", "SUMMARY:Neujahr (vorbei)", "END:VEVENT",
            "BEGIN:VEVENT", "UID:m1", "DTSTART;TZID=Europe/Berlin:20261010T190000", "DTEND;TZID=Europe/Berlin:20261010T210000",
            "SUMMARY:Konzert", "LOCATION:Halle\\, Saal 2", "END:VEVENT",
            "BEGIN:VEVENT", "UID:s1", "DTSTART;TZID=Europe/Berlin:20260105T180000", "RRULE:FREQ=WEEKLY;BYDAY=MO",
            "SUMMARY:Training", "END:VEVENT",
            "BEGIN:VEVENT", "UID:s2", "DTSTART:20250105T180000Z", "RRULE:FREQ=WEEKLY;UNTIL=20250601T000000Z",
            "SUMMARY:Alte Serie", "END:VEVENT",
            "BEGIN:VEVENT", "UID:nodate", "SUMMARY:Ohne Datum", "END:VEVENT",
            "END:VCALENDAR", "");

    private HttpServer server;
    private String base;
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final ICalFeedProvider provider = new ICalFeedProvider(new ICalFeedFetcher());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed.ics", ex -> {
            userAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes = FEED.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/calendar");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().add("Location", "/feed.ics");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> list(Map<String, Object> params) {
        return (Map<String, Object>) provider.executeAction("list_events", params, null);
    }

    @Test
    void metadata_needsNoCredentialsAndConnectsWithoutValidation() {
        assertThat(provider.getProviderId()).isEqualTo("ical-feed");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.requiresCredentials()).isFalse();
        assertThat(provider.fetchAccountInfo("|")).contains("iCal-Feed");
        assertThat(provider.getAvailableActions()).extracting(IntegrationAction::name).containsExactly("list_events");
    }

    @Test
    void listEvents_returnsEventsOfTheWindowSortedWithSeriesMasters() {
        Map<String, Object> result = list(Map.of("url", base + "/feed.ics", "from", "2026-10-01", "to", "2026-10-31",
                "timeZone", "Europe/Berlin"));

        List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
        assertThat(events).extracting(e -> e.get("uid")).containsExactly("s1", "h1", "m1");
        assertThat(events.get(0)).containsEntry("recurring", true).containsEntry("rrule", "FREQ=WEEKLY;BYDAY=MO");
        assertThat(events.get(2)).containsEntry("location", "Halle, Saal 2").doesNotContainKey("recurring");
        assertThat((Map<String, Object>) events.get(2).get("start")).containsEntry("dateTime", "2026-10-10T19:00:00+02:00");
        assertThat(result).containsEntry("calendarName", "Feiertage Test").containsEntry("source", "127.0.0.1")
                .containsEntry("count", 3).containsEntry("truncated", false)
                .containsEntry("from", "2026-09-30T22:00:00Z").containsKey("note");
        assertThat(userAgent.get()).isEqualTo("MySuricates-ICalFeed");
    }

    @Test
    void listEvents_limitTruncatesAndNoNoteWithoutSeries() {
        Map<String, Object> limited = list(Map.of("url", base + "/feed.ics", "from", "2026-10-01", "to", "2026-10-31", "limit", 1));
        assertThat(limited).containsEntry("count", 1).containsEntry("truncated", true);

        Map<String, Object> past = list(Map.of("url", base + "/feed.ics", "from", "2024-12-01", "to", "2024-12-31"));
        assertThat((List<?>) past.get("events")).isEmpty();
        assertThat(past).doesNotContainKey("note");
    }

    @Test
    void listEvents_followsRedirects() {
        Map<String, Object> result = list(Map.of("url", base + "/moved", "from", "2026-10-01", "to", "2026-10-31"));
        assertThat((List<?>) result.get("events")).isNotEmpty();
    }

    @Test
    void listEvents_validatesParameters() {
        assertThatThrownBy(() -> list(Map.of())).hasMessageContaining("url");
        assertThatThrownBy(() -> list(Map.of("url", base + "/feed.ics", "from", "2026-10-05", "to", "2026-10-01")))
                .hasMessageContaining("after");
        assertThatThrownBy(() -> list(Map.of("url", base + "/feed.ics", "timeZone", "Nowhere/Town")))
                .hasMessageContaining("time zone");
        assertThatThrownBy(() -> provider.executeAction("create_event", Map.of(), null)).hasMessageContaining("Unknown action");
    }

    @Test
    void validate_onlyWebUrls_webcalBecomesHttps() {
        assertThat(ICalFeedFetcher.validate("webcal://example.org/a.ics")).isEqualTo(URI.create("https://example.org/a.ics"));
        assertThat(ICalFeedFetcher.validate(" https://example.org/a.ics ")).isEqualTo(URI.create("https://example.org/a.ics"));
        assertThatThrownBy(() -> ICalFeedFetcher.validate("file:///etc/passwd")).hasMessageContaining("http");
        assertThatThrownBy(() -> ICalFeedFetcher.validate("ftp://example.org/a.ics")).hasMessageContaining("http");
        assertThatThrownBy(() -> ICalFeedFetcher.validate("jar:http://x!/y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalFeedFetcher.validate("https://user:pw@example.org/a.ics")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> ICalFeedFetcher.validate("https:///a.ics")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ICalFeedFetcher.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
