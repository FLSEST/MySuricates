package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.providers.ical.ICalDateTime;
import com.automation.testsupport.TestTargetGuards;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Event actions against the {@link FakeDavServer}; the request bodies and headers are part of the contract. */
@SuppressWarnings("unchecked")
class CalDavEventsTest {

    private static final String HOME = "/dav/calendars/alex/";
    private static final String WORK = HOME + "work/";

    private static String multistatus(String... responses) {
        return "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                + String.join("", responses) + "</d:multistatus>";
    }

    private static String item(String href, String etag, String ics) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop><d:getetag>" + etag
                + "</d:getetag><c:calendar-data>" + ics + "</c:calendar-data></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    private static String ics(String uid, String start, String summary) {
        return String.join("\r\n", "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:" + uid, "DTSTART:" + start,
                "SUMMARY:" + summary, "END:VEVENT", "END:VCALENDAR", "");
    }

    private static String calendarEntry(String name) {
        return "<d:response><d:href>" + HOME + name + "/</d:href><d:propstat><d:prop>"
                + "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    private FakeDavServer server;
    private CalDavCredentials cred;
    private CalDavEvents events;

    @BeforeEach
    void start() throws Exception {
        server = new FakeDavServer();
        cred = CalDavCredentials.parse(server.base() + "/dav|alex:pw");
        CalDavHttpClient http = new CalDavHttpClient(TestTargetGuards.loopbackOnly());
        events = new CalDavEvents(http, new CalDavDiscovery(http));
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void list_singleCalendar_sortsByStartAndAddsHrefEtagCalendar() {
        server.on("REPORT " + WORK, 207, multistatus(
                item(WORK + "b.ics", "\"2\"", ics("b", "20261005T100000Z", "Spaeter")),
                item(WORK + "a.ics", "\"1\"", ics("a", "20261002T100000Z", "Frueher"))));

        Map<String, Object> result = events.list(cred, Map.of("calendar", WORK, "from", "2026-10-01", "to", "2026-10-31"));

        List<Map<String, Object>> list = (List<Map<String, Object>>) result.get("events");
        assertThat(list).extracting(e -> e.get("summary")).containsExactly("Frueher", "Spaeter");
        assertThat(list.get(0)).containsEntry("href", WORK + "a.ics").containsEntry("etag", "\"1\"")
                .containsEntry("calendar", WORK).doesNotContainKey("_sortKey");
        assertThat(result).containsEntry("count", 2).containsEntry("truncated", false).doesNotContainKey("errors");
        assertThat(server.requests("REPORT").get(0).body()).contains("<c:expand start=\"20260930T220000Z\" end=\"20261031T230000Z\"/>");
    }

    @Test
    void list_limitTruncates_andRangeIsValidated() {
        server.on("REPORT " + WORK, 207, multistatus(
                item(WORK + "a.ics", "\"1\"", ics("a", "20261002T100000Z", "A")),
                item(WORK + "b.ics", "\"2\"", ics("b", "20261003T100000Z", "B"))));

        Map<String, Object> result = events.list(cred, Map.of("calendar", WORK, "from", "2026-10-01", "limit", 1));
        assertThat(result).containsEntry("count", 1).containsEntry("truncated", true);
        assertThat((List<?>) result.get("events")).hasSize(1);

        assertThatThrownBy(() -> events.list(cred, Map.of("calendar", WORK, "from", "2026-10-05", "to", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'to' must be after 'from'");
    }

    @Test
    void list_hrefOnForeignHostIsSkipped_andItemsWithoutCalendarData() {
        server.on("REPORT " + WORK, 207, multistatus(
                item("https://evil.example/x.ics", "\"9\"", ics("x", "20261002T100000Z", "Fremd")),
                "<d:response><d:href>" + WORK + "empty.ics</d:href><d:propstat><d:prop><d:getetag>\"3\"</d:getetag>"
                        + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
                item(WORK + "ok.ics", "\"4\"", ics("ok", "20261002T100000Z", "Eigen"))));

        List<Map<String, Object>> list = (List<Map<String, Object>>)
                events.list(cred, Map.of("calendar", WORK, "from", "2026-10-01")).get("events");

        assertThat(list).extracting(e -> e.get("summary")).containsExactly("Eigen");
    }

    @Test
    void list_allCalendars_oneFailingCalendarIsReportedNotFatal() {
        server.on("PROPFIND /dav/", 207, multistatus("<d:response><d:href>/dav/</d:href><d:propstat><d:prop>"
                + "<d:current-user-principal><d:href>/dav/p/</d:href></d:current-user-principal></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"));
        server.on("PROPFIND /dav/p/", 207, multistatus("<d:response><d:href>/dav/p/</d:href><d:propstat><d:prop>"
                + "<c:calendar-home-set><d:href>" + HOME + "</d:href></c:calendar-home-set></d:prop>"
                + "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"));
        server.on("PROPFIND " + HOME, 207, multistatus(calendarEntry("work"), calendarEntry("broken")));
        server.on("REPORT " + WORK, 207, multistatus(item(WORK + "a.ics", "\"1\"", ics("a", "20261002T100000Z", "A"))));
        server.on("REPORT " + HOME + "broken/", 403, "");

        Map<String, Object> result = events.list(cred, Map.of("from", "2026-10-01"));

        assertThat((List<?>) result.get("events")).hasSize(1);
        assertThat((List<String>) result.get("errors")).singleElement().asString()
                .startsWith(HOME + "broken/: ").contains("403");
    }

    @Test
    void list_explicitCalendarFailure_propagates() {
        server.on("REPORT " + WORK, 404, "");
        assertThatThrownBy(() -> events.list(cred, Map.of("calendar", WORK, "from", "2026-10-01")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("404");
    }

    @Test
    void create_putsNewObjectWithIfNoneMatch() {
        server.on("PUT *", new FakeDavServer.Reply(201, "", Map.of("ETag", "\"new\"")));

        Map<String, Object> out = events.create(cred, Map.of("calendar", WORK, "summary", "Treffen",
                "start", "2026-10-01T10:00:00", "location", "Berlin", "rrule", "FREQ=WEEKLY;COUNT=3"));

        FakeDavServer.Recorded put = server.requests("PUT").get(0);
        assertThat(put.path()).startsWith(WORK).endsWith(".ics");
        assertThat(put.headers()).containsEntry("If-None-Match", "*").doesNotContainKey("If-Match");
        assertThat(put.body()).contains("SUMMARY:Treffen", "LOCATION:Berlin", "RRULE:FREQ=WEEKLY;COUNT=3",
                "DTSTART;TZID=Europe/Berlin:20261001T100000", "DTEND;TZID=Europe/Berlin:20261001T110000");
        assertThat(out).containsEntry("created", true).containsEntry("etag", "\"new\"").containsEntry("calendar", WORK);
        assertThat(out.get("uid").toString()).endsWith("@mysuricates");
        assertThat(out.get("href")).isEqualTo(put.path());
    }

    @Test
    void create_requiresCalendarSummaryAndStart() {
        assertThatThrownBy(() -> events.create(cred, Map.of("calendar", WORK, "start", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("summary");
        assertThatThrownBy(() -> events.create(cred, Map.of("summary", "x", "start", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("calendar");
        assertThat(server.requests("PUT")).isEmpty();
    }

    @Test
    void update_patchesInPlace_keepsUnknownProperties_andUsesServerEtag() {
        String stored = String.join("\r\n", "BEGIN:VCALENDAR", "BEGIN:VEVENT", "UID:a", "DTSTART:20261002T100000Z",
                "SUMMARY:Alt", "LOCATION:Ort", "X-CUSTOM:bleibt", "END:VEVENT", "END:VCALENDAR", "");
        server.on("GET " + WORK + "a.ics", new FakeDavServer.Reply(200, stored, Map.of("ETag", "\"v1\"")));
        server.on("PUT " + WORK + "a.ics", new FakeDavServer.Reply(204, "", Map.of("ETag", "\"v2\"")));

        Map<String, Object> out = events.update(cred, Map.of("href", WORK + "a.ics", "summary", "Neu", "location", ""));

        FakeDavServer.Recorded put = server.requests("PUT").get(0);
        assertThat(put.headers()).containsEntry("If-Match", "\"v1\"").doesNotContainKey("If-None-Match");
        assertThat(put.body()).contains("SUMMARY:Neu", "X-CUSTOM:bleibt").doesNotContain("SUMMARY:Alt", "LOCATION:");
        assertThat(out).containsEntry("updated", true).containsEntry("etag", "\"v2\"");
        assertThat((List<String>) out.get("changed")).containsExactly("SUMMARY", "LOCATION");
    }

    @Test
    void update_explicitEtagWins_andNothingToUpdateFailsBeforeAnyRequest() {
        server.on("GET " + WORK + "a.ics", new FakeDavServer.Reply(200, ics("a", "20261002T100000Z", "Alt"), Map.of("ETag", "\"srv\"")));
        server.on("PUT " + WORK + "a.ics", 204, "");

        events.update(cred, Map.of("href", WORK + "a.ics", "etag", "\"mine\"", "rrule", ""));
        assertThat(server.requests("PUT").get(0).headers()).containsEntry("If-Match", "\"mine\"");

        int before = server.requests.size();
        assertThatThrownBy(() -> events.update(cred, Map.of("href", WORK + "a.ics")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nothing to update");
        assertThat(server.requests).hasSize(before);
    }

    @Test
    void delete_sendsIfMatchOnlyWhenEtagGiven() {
        server.on("DELETE *", 204, "");

        assertThat(events.delete(cred, Map.of("href", WORK + "a.ics", "etag", "\"1\"")))
                .containsEntry("deleted", true).containsEntry("href", WORK + "a.ics");
        events.delete(cred, Map.of("href", WORK + "b.ics"));

        List<FakeDavServer.Recorded> deletes = server.requests("DELETE");
        assertThat(deletes.get(0).headers()).containsEntry("If-Match", "\"1\"");
        assertThat(deletes.get(1).headers()).doesNotContainKey("If-Match");
    }

    @Test
    void endOrDefault_addsOneHourOrOneDay_andRejectsMixedKinds() {
        ICalDateTime timed = ICalDateTime.fromIso("2026-10-01T10:00:00", "Europe/Berlin");
        ICalDateTime allDay = ICalDateTime.fromIso("2026-10-01", "Europe/Berlin");

        assertThat(CalDavEvents.endOrDefault(timed, null, "Europe/Berlin").local()).isEqualTo(timed.local().plusHours(1));
        assertThat(CalDavEvents.endOrDefault(allDay, null, "Europe/Berlin").date()).isEqualTo(allDay.date().plusDays(1));
        assertThat(CalDavEvents.endOrDefault(timed, "2026-10-01T12:30:00", "Europe/Berlin").local().getMinute()).isEqualTo(30);
        assertThatThrownBy(() -> CalDavEvents.endOrDefault(timed, "2026-10-02", "Europe/Berlin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both be dates");
        assertThatThrownBy(() -> CalDavEvents.endOrDefault(allDay, "2026-10-02T10:00:00", "Europe/Berlin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
