package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteAccessResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new RemoteAccessResponse(true, "192.168.1.1", "myhost.local", false);

        assertThat(response.enabled()).isTrue();
        assertThat(response.ip()).isEqualTo("192.168.1.1");
        assertThat(response.hostname()).isEqualTo("myhost.local");
    }

    @Test
    void should_handleDisabledState_when_enabledIsFalse() {
        var response = new RemoteAccessResponse(false, "10.0.0.1", "server", false);

        assertThat(response.enabled()).isFalse();
        assertThat(response.ip()).isEqualTo("10.0.0.1");
        assertThat(response.hostname()).isEqualTo("server");
    }

    @Test
    void should_acceptNullIp_when_constructed() {
        var response = new RemoteAccessResponse(true, null, "host", false);

        assertThat(response.ip()).isNull();
        assertThat(response.hostname()).isEqualTo("host");
    }

    @Test
    void should_acceptNullHostname_when_constructed() {
        var response = new RemoteAccessResponse(true, "1.2.3.4", null, false);

        assertThat(response.ip()).isEqualTo("1.2.3.4");
        assertThat(response.hostname()).isNull();
    }

    @Test
    void should_acceptAllNullStrings_when_constructed() {
        var response = new RemoteAccessResponse(false, null, null, false);

        assertThat(response.enabled()).isFalse();
        assertThat(response.ip()).isNull();
        assertThat(response.hostname()).isNull();
    }

    @Test
    void should_setInContainer_when_constructed() {
        var response = new RemoteAccessResponse(false, null, null, true);

        assertThat(response.inContainer()).isTrue();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new RemoteAccessResponse(true, "192.168.1.1", "host", false);
        var b = new RemoteAccessResponse(true, "192.168.1.1", "host", false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentEnabled() {
        var a = new RemoteAccessResponse(true, "1.1.1.1", "host", false);
        var b = new RemoteAccessResponse(false, "1.1.1.1", "host", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentIp() {
        var a = new RemoteAccessResponse(true, "1.1.1.1", "host", false);
        var b = new RemoteAccessResponse(true, "2.2.2.2", "host", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentHostname() {
        var a = new RemoteAccessResponse(true, "1.1.1.1", "host-a", false);
        var b = new RemoteAccessResponse(true, "1.1.1.1", "host-b", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothNullStrings() {
        var a = new RemoteAccessResponse(false, null, null, false);
        var b = new RemoteAccessResponse(false, null, null, false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new RemoteAccessResponse(true, "10.0.0.1", "myhost", false);
        var str = response.toString();

        assertThat(str).contains("true");
        assertThat(str).contains("10.0.0.1");
        assertThat(str).contains("myhost");
    }
}
