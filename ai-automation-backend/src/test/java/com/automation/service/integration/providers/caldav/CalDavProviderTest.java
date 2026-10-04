package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationVerification;
import com.automation.testsupport.TestTargetGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("unchecked")
class CalDavProviderTest {

    private static final String PRINCIPAL = """
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop>
            <d:current-user-principal><d:href>/dav/principals/alex/</d:href></d:current-user-principal>
            <d:displayname>Alex S.</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""";
    private static final String HOME = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/dav/principals/alex/</d:href>
            <d:propstat><d:prop><c:calendar-home-set><d:href>/dav/calendars/alex/</d:href></c:calendar-home-set></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""";
    private static final String CALENDARS = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
            <d:response><d:href>/dav/calendars/alex/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/dav/calendars/alex/work/</d:href><d:propstat><d:prop>
              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><d:displayname>Arbeit</d:displayname>
              <a:calendar-color>#0082c9</a:calendar-color></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>/dav/calendars/alex/tasks/</d:href><d:propstat><d:prop>
              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
              <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            <d:response><d:href>https://evil.example/cal/</d:href><d:propstat><d:prop>
              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>""";

    private static String report(String... items) {
        StringBuilder sb = new StringBuilder("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">");
        for (int i = 0; i + 2 < items.length; i += 3) {
            sb.append("<d:response><d:href>").append(items[i]).append("</d:href><d:propstat><d:prop><d:getetag>")
                    .append(items[i + 1]).append("</d:getetag><c:calendar-data>").append(items[i + 2])
                    .append("</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
        }
        return sb.append("</d:multistatus>").toString();
    }

    private static final String SERIES_EXPANDED = String.join("\r\n", "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", "UID:s1", "RECURRENCE-ID:20261013T070000Z", "DTSTART:20261013T070000Z", "DTEND:20261013T073000Z",
            "SUMMARY:Runde", "END:VEVENT",
            "BEGIN:VEVENT", "UID:s1", "RECURRENCE-ID:20261006T070000Z", "DTSTART:20261006T070000Z", "DTEND:20261006T073000Z",
            "SUMMARY:Runde", "END:VEVENT", "END:VCALENDAR", "");
    private static final String SINGLE = String.join("\r\n", "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:e1",
            "DTSTART;VALUE=DATE:20261003", "SUMMARY:Feiertag &amp; Ruhe", "END:VEVENT", "END:VCALENDAR", "");

    private FakeDavServer server;
    private CalDavProvider provider;
    private String token;

    @BeforeEach
    void start() throws Exception {
        server = new FakeDavServer();
        provider = new CalDavProvider(new ObjectMapper(), new CalDavHttpClient(TestTargetGuards.loopbackOnly()));
        token = server.base() + "/dav|alex:app-pw";
        server.on("PROPFIND /dav/", 207, PRINCIPAL)
                .on("PROPFIND /dav/principals/alex/", 207, HOME)
                .on("PROPFIND /dav/calendars/alex/", 207, CALENDARS);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void metadata_directAuthWithoutOAuth_andSimulatedUntilListed() {
        assertThat(provider.getProviderId()).isEqualTo("caldav");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.requiresCredentials()).isTrue();
        assertThat(provider.getAvailableActions()).extracting(IntegrationAction::name)
                .containsExactly("list_calendars", "list_events", "create_event", "update_event", "delete_event");
        assertThat(provider.getVerification()).isIn(IntegrationVerification.VERIFIED, IntegrationVerification.SIMULATED);
    }

    @Test
    void fetchAccountInfo_readsPrincipalAndDisplayName() {
        String info = provider.fetchAccountInfo(token);
        assertThat(info).contains("\"name\":\"Alex S.\"", "\"user\":\"alex\"", "\"principal\":\"/dav/principals/alex/\"")
                .doesNotContain("app-pw");
        assertThat(server.requests.get(0).headers()).containsEntry("Depth", "0");
    }

    @Test
    void fetchAccountInfo_nullForWrongLoginOrBadUrl() {
        server.on("PROPFIND /dav/x/", 401, "");
        assertThat(provider.fetchAccountInfo(server.base() + "/dav/x|alex:wrong")).isNull();
        assertThat(provider.fetchAccountInfo("ftp://x|alex:pw")).isNull();
        assertThat(provider.fetchAccountInfo(null)).isNull();
    }

    @Test
    void listCalendars_walksPrincipalHomeAndKeepsEventCalendarsOfTheSameServer() {
        Map<String, Object> result = (Map<String, Object>) provider.executeAction("list_calendars", Map.of(), token);
        List<Map<String, Object>> calendars = (List<Map<String, Object>>) result.get("calendars");
        assertThat(calendars).hasSize(1);
        assertThat(calendars.get(0)).containsEntry("name", "Arbeit").containsEntry("href", "/dav/calendars/alex/work/")
                .containsEntry("url", server.base() + "/dav/calendars/alex/work/").containsEntry("color", "#0082c9");
        assertThat(server.requests("PROPFIND")).extracting(FakeDavServer.Recorded::path)
                .containsExactly("/dav/", "/dav/principals/alex/", "/dav/calendars/alex/");
        assertThat(server.requests("PROPFIND").get(2).headers()).containsEntry("Depth", "1");
    }

    @Test
    void listCalendars_foreignHrefFromServerIsRefused() {
        // the fake home lists https://evil.example/cal/ — it must never become a request target
        provider.executeAction("list_calendars", Map.of(), token);
        assertThat(server.requests).noneMatch(r -> r.path().contains("evil"));
    }

    @Test
    void listEvents_reportsWithExpandSortsInstancesAndAddsHrefEtag() {
        server.replace("PROPFIND /dav/calendars/alex/", 207, CALENDARS.replace("<d:href>https://evil.example/cal/</d:href>",
                "<d:href>/dav/calendars/alex/other/</d:href>"));
        server.on("REPORT /dav/calendars/alex/work/", 207, report("/dav/calendars/alex/work/s1.ics", "\"e-s\"", SERIES_EXPANDED));
        server.on("REPORT /dav/calendars/alex/other/", 207, report("/dav/calendars/alex/other/e1.ics", "\"e-1\"", SINGLE));

        Map<String, Object> result = (Map<String, Object>) provider.executeAction("list_events",
                Map.of("from", "2026-10-01", "to", "2026-10-31", "timeZone", "UTC"), token);

        List<Map<String, Object>> events = (List<Map<String, Object>>) result.get("events");
        assertThat(events).extracting(e -> e.get("summary")).containsExactly("Feiertag & Ruhe", "Runde", "Runde");
        assertThat(events.get(1)).containsEntry("recurrenceId", "20261006T070000Z")
                .containsEntry("href", "/dav/calendars/alex/work/s1.ics").containsEntry("etag", "\"e-s\"")
                .containsEntry("calendar", "/dav/calendars/alex/work/").doesNotContainKey("_sortKey");
        assertThat(result).containsEntry("count", 3).containsEntry("truncated", false)
                .containsEntry("from", "2026-10-01T00:00:00Z").containsEntry("to", "2026-11-01T00:00:00Z");

        FakeDavServer.Recorded report = server.requests("REPORT").get(0);
        assertThat(report.headers()).containsEntry("Depth", "1");
        assertThat(report.body()).contains("<c:expand start=\"20261001T000000Z\" end=\"20261101T000000Z\"/>",
                "<c:time-range start=\"20261001T000000Z\" end=\"20261101T000000Z\"/>");
    }

    @Test
    void listEvents_singleCalendarLimitAndErrors() {
        server.on("REPORT /dav/calendars/alex/work/", 207, report("/dav/calendars/alex/work/s1.ics", "\"e\"", SERIES_EXPANDED));
        Map<String, Object> limited = (Map<String, Object>) provider.executeAction("list_events",
                Map.of("calendar", "/dav/calendars/alex/work", "from", "2026-10-01", "to", "2026-10-31", "limit", 1), token);
        assertThat(limited).containsEntry("count", 1).containsEntry("truncated", true);
        assertThat(server.requests("PROPFIND")).isEmpty();

        assertThatThrownBy(() -> provider.executeAction("list_events",
                Map.of("calendar", "/dav/calendars/alex/missing/"), token)).hasMessageContaining("404");
        assertThatThrownBy(() -> provider.executeAction("list_events",
                Map.of("from", "2026-10-05", "to", "2026-10-01"), token)).hasMessageContaining("after");
        assertThatThrownBy(() -> provider.executeAction("list_events",
                Map.of("calendar", "https://evil.example/cal/"), token)).hasMessageContaining("another server");
    }

    @Test
    void listEvents_oneBrokenCalendarDoesNotHideTheOthers() {
        server.replace("PROPFIND /dav/calendars/alex/", 207, CALENDARS.replace("<d:href>https://evil.example/cal/</d:href>",
                "<d:href>/dav/calendars/alex/broken/</d:href>"));
        server.on("REPORT /dav/calendars/alex/work/", 207, report("/dav/calendars/alex/work/e1.ics", "\"e\"", SINGLE));
        server.on("REPORT /dav/calendars/alex/broken/", 500, "kaputt");

        Map<String, Object> result = (Map<String, Object>) provider.executeAction("list_events",
                Map.of("from", "2026-10-01", "to", "2026-10-31"), token);
        assertThat((List<?>) result.get("events")).hasSize(1);
        assertThat((List<String>) result.get("errors")).singleElement().asString().contains("/dav/calendars/alex/broken/", "500");
    }

    @Test
    void createEvent_putsNewIcsWithIfNoneMatch() {
        server.on("PUT *", new FakeDavServer.Reply(201, "", Map.of("ETag", "\"c1\""))); // resource name is random

        Map<String, Object> created = (Map<String, Object>) provider.executeAction("create_event", Map.of(
                "calendar", "/dav/calendars/alex/work/", "summary", "Planung",
                "start", "2026-10-05T10:00:00", "end", "2026-10-05T11:00:00", "timeZone", "Europe/Berlin",
                "rrule", "FREQ=WEEKLY;COUNT=2", "location", "Raum 1"), token);

        FakeDavServer.Recorded put = server.requests("PUT").get(0);
        assertThat(put.path()).startsWith("/dav/calendars/alex/work/").endsWith(".ics");
        assertThat(put.headers()).containsEntry("If-None-Match", "*").containsEntry("Content-Type", "text/calendar; charset=utf-8");
        assertThat(put.body()).contains("SUMMARY:Planung", "DTSTART;TZID=Europe/Berlin:20261005T100000",
                "RRULE:FREQ=WEEKLY;COUNT=2", "LOCATION:Raum 1", "BEGIN:VTIMEZONE", "UID:" + created.get("uid"));
        assertThat(created).containsEntry("created", true).containsEntry("href", put.path()).containsEntry("etag", "\"c1\"");
    }

    @Test
    void createEvent_defaultsEndAndValidatesInput() {
        server.on("PUT *", 201, "");
        provider.executeAction("create_event", Map.of("calendar", "/dav/calendars/alex/work/", "summary", "Tag",
                "start", "2026-10-03"), token);
        assertThat(server.requests("PUT").get(0).body()).contains("DTSTART;VALUE=DATE:20261003", "DTEND;VALUE=DATE:20261004");

        provider.executeAction("create_event", Map.of("calendar", "/dav/calendars/alex/work/", "summary", "Kurz",
                "start", "2026-10-03T08:00:00Z"), token);
        assertThat(server.requests("PUT").get(1).body()).contains("DTSTART:20261003T080000Z", "DTEND:20261003T090000Z");

        assertThatThrownBy(() -> provider.executeAction("create_event", Map.of("calendar", "/c/", "summary", "x"), token))
                .hasMessageContaining("start");
        assertThatThrownBy(() -> provider.executeAction("create_event", Map.of("calendar", "/c/", "summary", "x",
                "start", "2026-10-03", "end", "2026-10-03T10:00:00"), token)).hasMessageContaining("both");
        assertThatThrownBy(() -> provider.executeAction("create_event", Map.of("calendar", "/c/", "summary", "x",
                "start", "2026-10-03", "rrule", "FREQ=DAILY\nX:1"), token)).hasMessageContaining("rrule");
        assertThat(server.requests("PUT")).hasSize(2);
    }

    @Test
    void updateEvent_getsPatchesAndPutsWithIfMatch() {
        String stored = String.join("\r\n", "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:e1", "SEQUENCE:2",
                "DTSTART:20261005T080000Z", "SUMMARY:Alt", "ATTENDEE:mailto:a@example.org", "END:VEVENT", "END:VCALENDAR", "");
        server.on("GET /dav/calendars/alex/work/e1.ics", new FakeDavServer.Reply(200, stored, Map.of("ETag", "\"v1\"")));
        server.on("PUT /dav/calendars/alex/work/e1.ics", new FakeDavServer.Reply(204, "", Map.of("ETag", "\"v2\"")));

        Map<String, Object> result = (Map<String, Object>) provider.executeAction("update_event",
                Map.of("href", "/dav/calendars/alex/work/e1.ics", "summary", "Neu", "location", "Raum 2"), token);

        FakeDavServer.Recorded put = server.requests("PUT").get(0);
        assertThat(put.headers()).containsEntry("If-Match", "\"v1\"");
        assertThat(put.body()).contains("SUMMARY:Neu", "LOCATION:Raum 2", "ATTENDEE:mailto:a@example.org", "SEQUENCE:3")
                .doesNotContain("SUMMARY:Alt");
        assertThat(result).containsEntry("updated", true).containsEntry("etag", "\"v2\"");

        provider.executeAction("update_event", Map.of("href", "/dav/calendars/alex/work/e1.ics", "etag", "\"mine\"",
                "summary", "X"), token);
        assertThat(server.requests("PUT").get(1).headers()).containsEntry("If-Match", "\"mine\"");
    }

    @Test
    void updateEvent_conflictAndNothingToDo() {
        server.on("GET /dav/calendars/alex/work/e1.ics", new FakeDavServer.Reply(200,
                "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:e1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", Map.of("ETag", "\"v1\"")));
        server.on("PUT /dav/calendars/alex/work/e1.ics", 412, "");

        assertThatThrownBy(() -> provider.executeAction("update_event",
                Map.of("href", "/dav/calendars/alex/work/e1.ics", "etag", "\"stale\"", "summary", "x"), token))
                .hasMessageContaining("412");
        assertThatThrownBy(() -> provider.executeAction("update_event",
                Map.of("href", "/dav/calendars/alex/work/e1.ics"), token)).hasMessageContaining("Nothing to update");
    }

    @Test
    void deleteEvent_withAndWithoutEtag() {
        server.on("DELETE /dav/calendars/alex/work/e1.ics", 204, "");
        Map<String, Object> result = (Map<String, Object>) provider.executeAction("delete_event",
                Map.of("href", "/dav/calendars/alex/work/e1.ics", "etag", "\"v1\""), token);
        assertThat(result).containsEntry("deleted", true);
        assertThat(server.requests("DELETE").get(0).headers()).containsEntry("If-Match", "\"v1\"");
        assertThatThrownBy(() -> provider.executeAction("delete_event", Map.of(), token)).hasMessageContaining("href");
    }

    @Test
    void unknownActionAndMissingConnection() {
        assertThatThrownBy(() -> provider.executeAction("nope", Map.of(), token)).hasMessageContaining("Unknown action");
        assertThatThrownBy(() -> provider.executeAction("list_calendars", Map.of(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not connected");
    }
}
