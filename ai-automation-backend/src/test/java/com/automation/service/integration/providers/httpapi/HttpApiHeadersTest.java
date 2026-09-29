package com.automation.service.integration.providers.httpapi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpApiHeadersTest {

    @Test
    void framingAndHopByHopHeadersAreForbidden_caseInsensitively() {
        for (String name : new String[]{"Host", "content-length", "CONNECTION", "Transfer-Encoding", "Upgrade",
                "Expect", "Keep-Alive", "TE", "Trailer", "Proxy-Authorization", "Proxy-Connection"}) {
            assertThat(HttpApiHeaders.isForbidden(name)).as(name).isTrue();
        }
    }

    @Test
    void ordinaryHeadersAreAllowed() {
        for (String name : new String[]{"Authorization", "X-Api-Key", "Accept", "Content-Type", "Hosting"}) {
            assertThat(HttpApiHeaders.isForbidden(name)).as(name).isFalse();
        }
    }

    @Test
    void controlCharactersInValuesAreIllegal_exceptTab() {
        assertThat(HttpApiHeaders.hasIllegalValueChar("a\r\nb")).isTrue();
        assertThat(HttpApiHeaders.hasIllegalValueChar("a\nb")).isTrue();
        assertThat(HttpApiHeaders.hasIllegalValueChar("a\0b")).isTrue();
        assertThat(HttpApiHeaders.hasIllegalValueChar("a\u007fb")).isTrue();
        assertThat(HttpApiHeaders.hasIllegalValueChar("a\tb")).isFalse();
        assertThat(HttpApiHeaders.hasIllegalValueChar("Bearer abc.def-123 ü")).isFalse();
        assertThat(HttpApiHeaders.hasIllegalValueChar("")).isFalse();
    }
}
