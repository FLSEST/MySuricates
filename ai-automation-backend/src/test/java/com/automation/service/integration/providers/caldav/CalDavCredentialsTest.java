package com.automation.service.integration.providers.caldav;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalDavCredentialsTest {

    @Test
    void parse_splitsUrlUserAndPassword_passwordMayContainSeparators() {
        CalDavCredentials c = CalDavCredentials.parse("https://cloud.example/remote.php/dav|flo:pa|ss:w0rd");
        assertThat(c.baseUrl()).isEqualTo(URI.create("https://cloud.example/remote.php/dav/"));
        assertThat(c.username()).isEqualTo("flo");
        assertThat(c.password()).isEqualTo("pa|ss:w0rd");
        String decoded = new String(Base64.getDecoder().decode(c.basicAuthHeader().substring(6)), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("flo:pa|ss:w0rd");
    }

    @Test
    void parse_rejectsMissingOrMalformedParts() {
        assertThatThrownBy(() -> CalDavCredentials.parse(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CalDavCredentials.parse("https://x/dav")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalDavCredentials.parse("https://x/dav|nopassword")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalDavCredentials.parse("https://x/dav|:pw")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalDavCredentials.parse("https://x/dav|user:")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baseUrl_onlyHttpOrHttpsWithHostAndWithoutUserInfo() {
        assertThatThrownBy(() -> CalDavCredentials.parse("ftp://x/dav|u:p")).hasMessageContaining("http");
        assertThatThrownBy(() -> CalDavCredentials.parse("file:///etc/passwd|u:p")).hasMessageContaining("http");
        assertThatThrownBy(() -> CalDavCredentials.parse("cloud.example/dav|u:p")).hasMessageContaining("http");
        assertThatThrownBy(() -> CalDavCredentials.parse("https:///dav|u:p")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalDavCredentials.parse("https://u:p@x/dav|u:p")).hasMessageContaining("second field");
        assertThat(CalDavCredentials.parse("HTTP://127.0.0.1:5232|u:p").baseUrl()).hasToString("http://127.0.0.1:5232/");
    }

    @Test
    void resolve_acceptsHrefsOnTheSameOriginOnly() {
        CalDavCredentials c = CalDavCredentials.parse("https://cloud.example/remote.php/dav|u:p");
        assertThat(c.resolve("/remote.php/dav/calendars/u/personal/")).hasToString("https://cloud.example/remote.php/dav/calendars/u/personal/");
        assertThat(c.resolve("calendars/u/")).hasToString("https://cloud.example/remote.php/dav/calendars/u/");
        assertThat(c.resolve("https://CLOUD.example:443/x.ics")).hasToString("https://CLOUD.example:443/x.ics");
        assertThat(c.resolve(null)).isEqualTo(c.baseUrl());

        assertThatThrownBy(() -> c.resolve("https://evil.example/steal")).hasMessageContaining("another server");
        assertThatThrownBy(() -> c.resolve("//evil.example/steal")).hasMessageContaining("another server");
        assertThatThrownBy(() -> c.resolve("http://cloud.example/x")).hasMessageContaining("another server");
        assertThatThrownBy(() -> c.resolve("https://cloud.example:8443/x")).hasMessageContaining("another server");
        assertThatThrownBy(() -> c.resolve("ht tp://bad")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveCollection_addsTheTrailingSlash() {
        CalDavCredentials c = CalDavCredentials.parse("http://127.0.0.1:5232|u:p");
        assertThat(c.resolveCollection("/flo/work")).hasToString("http://127.0.0.1:5232/flo/work/");
        assertThat(c.resolveCollection("/flo/work/")).hasToString("http://127.0.0.1:5232/flo/work/");
    }

    @Test
    void toString_neverShowsThePassword() {
        assertThat(CalDavCredentials.parse("https://x/dav|flo:s3cret").toString()).doesNotContain("s3cret").contains("***");
    }
}
