package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.providers.caldav.CalDavXml.DavResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalDavXmlTest {

    private static final String CALENDARS = """
            <?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/"
                           xmlns:x="http://apple.com/ns/ical/">
              <d:response>
                <d:href>/dav/calendars/flo/</d:href>
                <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/calendars/flo/personal/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <d:displayname>Persönlich</d:displayname>
                    <c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    <cs:getctag>ctag-1</cs:getctag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><x:calendar-color/><c:calendar-description/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""";

    @Test
    void parseMultistatus_readsHrefsAndOnlySuccessfulPropstats() {
        List<DavResponse> responses = CalDavXml.parseMultistatus(CALENDARS);
        assertThat(responses).extracting(DavResponse::href)
                .containsExactly("/dav/calendars/flo/", "/dav/calendars/flo/personal/");

        DavResponse personal = responses.get(1);
        assertThat(personal.hasChild(CalDavXml.DAV, "resourcetype", CalDavXml.CALDAV, "calendar")).isTrue();
        assertThat(responses.get(0).hasChild(CalDavXml.DAV, "resourcetype", CalDavXml.CALDAV, "calendar")).isFalse();
        assertThat(personal.text(CalDavXml.DAV, "displayname")).isEqualTo("Persönlich");
        assertThat(personal.text(CalDavXml.CALSERVER, "getctag")).isEqualTo("ctag-1");
        assertThat(personal.components()).containsExactly("VEVENT", "VTODO");
        // properties reported with 404 are absent, not empty strings
        assertThat(personal.text(CalDavXml.APPLE, "calendar-color")).isNull();
    }

    @Test
    void parseMultistatus_nestedHrefsAndDefaultNamespace() {
        String xml = """
                <multistatus xmlns="DAV:"><response><href>/</href><propstat><prop>
                <current-user-principal><href>/flo/</href></current-user-principal></prop>
                <status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""";
        DavResponse r = CalDavXml.parseMultistatus(xml).get(0);
        assertThat(r.hrefIn(CalDavXml.DAV, "current-user-principal")).isEqualTo("/flo/");
        assertThat(r.hrefIn(CalDavXml.CALDAV, "calendar-home-set")).isNull();
    }

    @Test
    void parseMultistatus_keepsCalendarDataLineStructure() {
        String xml = "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:response><d:href>/a.ics</d:href>"
                + "<d:propstat><d:prop><d:getetag>\"e1\"</d:getetag><c:calendar-data>BEGIN:VCALENDAR\r\nSUMMARY:a &amp; b\r\n END\r\n"
                + "</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>";
        DavResponse r = CalDavXml.parseMultistatus(xml).get(0);
        assertThat(r.rawText(CalDavXml.CALDAV, "calendar-data")).contains("SUMMARY:a & b\n END");
        assertThat(r.text(CalDavXml.DAV, "getetag")).isEqualTo("\"e1\"");
    }

    @Test
    void parseMultistatus_refusesExternalEntities(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE m [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&x;</d:href></d:response></d:multistatus>";
        assertThatThrownBy(() -> CalDavXml.parseMultistatus(xxe))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("TOP-SECRET");
    }

    @Test
    void parseMultistatus_refusesEntityExpansionBombs() {
        String bomb = "<?xml version=\"1.0\"?><!DOCTYPE m [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">]>"
                + "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&b;</d:href></d:response></d:multistatus>";
        assertThatThrownBy(() -> CalDavXml.parseMultistatus(bomb)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseMultistatus_rejectsNonMultistatusAndGarbage() {
        assertThatThrownBy(() -> CalDavXml.parseMultistatus("<html><body>Login</body></html>"))
                .hasMessageContaining("multistatus");
        assertThatThrownBy(() -> CalDavXml.parseMultistatus("not xml at all"))
                .hasMessageContaining("unreadable");
    }

    @Test
    void isSuccess_readsTheStatusLine() {
        assertThat(CalDavXml.isSuccess("HTTP/1.1 200 OK")).isTrue();
        assertThat(CalDavXml.isSuccess("HTTP/1.1 207 Multi-Status")).isTrue();
        assertThat(CalDavXml.isSuccess("HTTP/1.1 404 Not Found")).isFalse();
        assertThat(CalDavXml.isSuccess("garbage")).isFalse();
        assertThat(CalDavXml.isSuccess(null)).isFalse();
    }

    @Test
    void calendarQuery_containsTimeRangeAndExpand() {
        String q = CalDavXml.calendarQuery("20261001T000000Z", "20261101T000000Z");
        assertThat(q).contains("<c:expand start=\"20261001T000000Z\" end=\"20261101T000000Z\"/>",
                "<c:time-range start=\"20261001T000000Z\" end=\"20261101T000000Z\"/>", "name=\"VEVENT\"");
    }
}
