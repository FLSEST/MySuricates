package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.providers.caldav.CalDavHttpClient.DavResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalDavHttpClientTest {

    private FakeDavServer server;
    private CalDavCredentials cred;
    private final CalDavHttpClient client = new CalDavHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = new FakeDavServer();
        cred = CalDavCredentials.parse(server.base() + "/dav|alex:geheim");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private URI uri(String path) {
        return URI.create(server.base() + path);
    }

    @Test
    void propfind_sendsMethodDepthXmlAndBasicAuth() {
        server.on("PROPFIND /dav/", 207, "<multistatus xmlns=\"DAV:\"/>");

        DavResult result = client.propfind(cred, uri("/dav/"), 0, "<propfind/>");

        assertThat(result.status()).isEqualTo(207);
        FakeDavServer.Recorded r = server.requests.get(0);
        assertThat(r.method()).isEqualTo("PROPFIND");
        assertThat(r.headers()).containsEntry("Depth", "0")
                .containsEntry("Authorization", "Basic YWxleDpnZWhlaW0=")
                .containsEntry("Content-Type", "application/xml; charset=utf-8");
        assertThat(r.body()).isEqualTo("<propfind/>");
    }

    @Test
    void report_usesDepthOne() {
        server.on("REPORT /dav/cal/", 207, "<multistatus xmlns=\"DAV:\"/>");
        client.report(cred, uri("/dav/cal/"), 1, "<q/>");
        assertThat(server.requests.get(0).method()).isEqualTo("REPORT");
        assertThat(server.requests.get(0).headers()).containsEntry("Depth", "1");
    }

    @Test
    void put_createOnlySendsIfNoneMatch_updateSendsIfMatch_andReturnsEtag() {
        server.on("PUT /dav/cal/a.ics", new FakeDavServer.Reply(201, "", Map.of("ETag", "\"new\"")));

        DavResult created = client.put(cred, uri("/dav/cal/a.ics"), "BEGIN:VCALENDAR", null, true);
        client.put(cred, uri("/dav/cal/a.ics"), "BEGIN:VCALENDAR", "\"old\"", false);

        assertThat(created.etag()).isEqualTo("\"new\"");
        FakeDavServer.Recorded create = server.requests.get(0);
        assertThat(create.headers()).containsEntry("If-None-Match", "*").doesNotContainKey("If-Match")
                .containsEntry("Content-Type", "text/calendar; charset=utf-8");
        assertThat(create.body()).isEqualTo("BEGIN:VCALENDAR");
        assertThat(server.requests.get(1).headers()).containsEntry("If-Match", "\"old\"").doesNotContainKey("If-None-Match");
    }

    @Test
    void getAndDelete() {
        server.on("GET /dav/cal/a.ics", new FakeDavServer.Reply(200, "BEGIN:VCALENDAR", Map.of("ETag", "\"e\"")));
        server.on("DELETE /dav/cal/a.ics", 204, "");

        assertThat(client.get(cred, uri("/dav/cal/a.ics")).body()).isEqualTo("BEGIN:VCALENDAR");
        client.delete(cred, uri("/dav/cal/a.ics"), "\"e\"");
        client.delete(cred, uri("/dav/cal/a.ics"), null);

        assertThat(server.requests.get(0).headers()).containsEntry("Accept", "text/calendar");
        assertThat(server.requests("DELETE").get(0).headers()).containsEntry("If-Match", "\"e\"");
        assertThat(server.requests("DELETE").get(1).headers()).doesNotContainKey("If-Match");
    }

    @Test
    void errors_areMappedWithoutLeakingThePassword() {
        server.on("GET /401", 401, "").on("GET /403", 403, "").on("GET /404", 404, "")
                .on("GET /405", 405, "").on("GET /412", 412, "").on("GET /500", 500, "boom geheim-body");

        assertThatThrownBy(() -> client.get(cred, uri("/401"))).hasMessage("CalDAV authentication failed — check user name and app password");
        assertThatThrownBy(() -> client.get(cred, uri("/403"))).hasMessageContaining("access denied (403)");
        assertThatThrownBy(() -> client.get(cred, uri("/404"))).hasMessageContaining("not found (404): /404");
        assertThatThrownBy(() -> client.get(cred, uri("/405"))).hasMessageContaining("does not allow GET");
        assertThatThrownBy(() -> client.get(cred, uri("/412"))).hasMessageContaining("precondition failed (412)");
        assertThatThrownBy(() -> client.get(cred, uri("/500"))).hasMessageContaining("CalDAV error 500 for GET /500: boom");
        for (String path : new String[]{"/401", "/403", "/404", "/412", "/500"}) {
            assertThatThrownBy(() -> client.get(cred, uri(path))).hasMessageNotContaining(":geheim").hasMessageNotContaining("Zmxv");
        }
    }

    @Test
    void redirects_areReportedNotFollowed() {
        server.on("PROPFIND /.well-known/caldav", new FakeDavServer.Reply(301, "", Map.of("Location", "/remote.php/dav/")));
        server.on("PROPFIND /remote.php/dav/", 207, "<multistatus xmlns=\"DAV:\"/>");

        assertThatThrownBy(() -> client.propfind(cred, uri("/.well-known/caldav"), 0, "<p/>"))
                .hasMessageContaining("redirects PROPFIND /.well-known/caldav to /remote.php/dav/");
        assertThat(server.requests).hasSize(1);
    }

    @Test
    void longErrorBodiesAreAbbreviated() {
        server.on("GET /x", 500, "x".repeat(1000));
        assertThatThrownBy(() -> client.get(cred, uri("/x")))
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(400));
    }

    @Test
    void transportFailure_isWrappedWithHostOnly() {
        CalDavCredentials dead = CalDavCredentials.parse("http://127.0.0.1:1/dav|alex:geheim");
        assertThatThrownBy(() -> client.get(dead, URI.create("http://127.0.0.1:1/dav/x.ics")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("CalDAV request failed (GET 127.0.0.1)")
                .hasMessageNotContaining("geheim");
    }
}
