package com.automation.service.integration.providers.caldav;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** principal -> calendar-home -> calendars, including the fallbacks for servers that skip a step. */
@SuppressWarnings("unchecked")
class CalDavDiscoveryTest {

    private static String ms(String inner) {
        return "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" "
                + "xmlns:a=\"http://apple.com/ns/ical/\" xmlns:cs=\"http://calendarserver.org/ns/\">" + inner + "</d:multistatus>";
    }

    private static String response(String href, String props) {
        return "<d:response><d:href>" + href + "</d:href><d:propstat><d:prop>" + props
                + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>";
    }

    private FakeDavServer server;
    private CalDavCredentials cred;
    private CalDavDiscovery discovery;

    @BeforeEach
    void start() throws Exception {
        server = new FakeDavServer();
        cred = CalDavCredentials.parse(server.base() + "/dav|alex:pw");
        discovery = new CalDavDiscovery(new CalDavHttpClient());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void principal_readsHrefAndDisplayName() {
        server.on("PROPFIND /dav/", 207, ms(response("/dav/",
                "<d:current-user-principal><d:href>/dav/principals/alex</d:href></d:current-user-principal>"
                        + "<d:displayname>Alex</d:displayname>")));

        CalDavDiscovery.Principal p = discovery.principal(cred);

        assertThat(p.url().getPath()).isEqualTo("/dav/principals/alex/");
        assertThat(p.displayName()).isEqualTo("Alex");
        assertThat(server.requests("PROPFIND").get(0).headers()).containsEntry("Depth", "0");
    }

    @Test
    void principal_fallsBackToBaseUrl_whenServerNamesNone() {
        server.on("PROPFIND /dav/", 207, ms(response("/dav/", "<d:displayname>Nur Name</d:displayname>")));
        assertThat(discovery.principal(cred).url()).isEqualTo(cred.baseUrl());

        server.replace("PROPFIND /dav/", 207, ms(""));
        CalDavDiscovery.Principal empty = discovery.principal(cred);
        assertThat(empty.url()).isEqualTo(cred.baseUrl());
        assertThat(empty.displayName()).isNull();
    }

    @Test
    void calendarHome_usesHomeSet_orFallsBackToPrincipal() {
        URI principal = cred.resolveCollection("/dav/principals/alex/");
        server.on("PROPFIND /dav/principals/alex/", 207, ms(response("/dav/principals/alex/",
                "<c:calendar-home-set><d:href>/dav/calendars/alex</d:href></c:calendar-home-set>")));
        assertThat(discovery.calendarHome(cred, principal).getPath()).isEqualTo("/dav/calendars/alex/");

        server.replace("PROPFIND /dav/principals/alex/", 207, ms(response("/dav/principals/alex/", "<d:displayname>x</d:displayname>")));
        assertThat(discovery.calendarHome(cred, principal)).isEqualTo(principal);
    }

    @Test
    void calendars_filtersNonCalendarsAndTodoOnlyAndForeignHosts_andFillsOptionalFields() {
        server.on("PROPFIND /dav/", 207, ms(response("/dav/", "<d:current-user-principal><d:href>/dav/p/</d:href></d:current-user-principal>")));
        server.on("PROPFIND /dav/p/", 207, ms(response("/dav/p/", "<c:calendar-home-set><d:href>/dav/cal/</d:href></c:calendar-home-set>")));
        String cal = "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>";
        server.on("PROPFIND /dav/cal/", 207, ms(
                response("/dav/cal/", "<d:resourcetype><d:collection/></d:resourcetype>")
                        + response("/dav/cal/work/", cal + "<d:displayname>Arbeit</d:displayname>"
                        + "<c:calendar-description>Termine</c:calendar-description><a:calendar-color>#fff</a:calendar-color>"
                        + "<cs:getctag>ct-1</cs:getctag>"
                        + "<c:supported-calendar-component-set><c:comp name=\"VEVENT\"/><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>")
                        + response("/dav/cal/todos/", cal
                        + "<c:supported-calendar-component-set><c:comp name=\"VTODO\"/></c:supported-calendar-component-set>")
                        + response("https://evil.example/cal/", cal)
                        + response("/dav/cal/plain", cal)));

        List<Map<String, Object>> calendars = discovery.calendars(cred);

        assertThat(calendars).extracting(c -> c.get("name")).containsExactly("Arbeit", "plain");
        Map<String, Object> work = calendars.get(0);
        assertThat(work).containsEntry("href", "/dav/cal/work/").containsEntry("description", "Termine")
                .containsEntry("color", "#fff").containsEntry("ctag", "ct-1");
        assertThat((List<String>) work.get("components")).containsExactly("VEVENT", "VTODO");
        assertThat(work.get("url").toString()).isEqualTo(server.base() + "/dav/cal/work/");
        assertThat(calendars.get(1)).containsEntry("href", "/dav/cal/plain/")
                .doesNotContainKeys("description", "color", "ctag", "components");
        assertThat(server.requests("PROPFIND").get(2).headers()).containsEntry("Depth", "1");
    }

    @Test
    void sameServer_returnsNullForForeignHost() {
        assertThat(CalDavDiscovery.sameServer(cred, "https://evil.example/x/")).isNull();
        assertThat(CalDavDiscovery.sameServer(cred, "/dav/x").getPath()).isEqualTo("/dav/x/");
    }
}
