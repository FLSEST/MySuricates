package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    /**
     * ba6a91a6: the left part of the chain is whatever the client sent. Behind a proxy that appends,
     * {@code X-Forwarded-For: <spoof>, <real client>} must count as the real client.
     */
    @Test
    void trustedProxyUsesRightMostUntrustedAddress() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1, ::1");

        assertThat(resolver.resolve(request("127.0.0.1", " 203.0.113.7 , 10.0.0.2"))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("127.0.0.1", "203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void spoofedLeftEntriesDoNotChangeTheBucket() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");

        assertThat(resolver.resolve(request("127.0.0.1", "1.1.1.1, 198.51.100.4"))).isEqualTo("198.51.100.4");
        assertThat(resolver.resolve(request("127.0.0.1", "2.2.2.2, 3.3.3.3, 198.51.100.4"))).isEqualTo("198.51.100.4");
    }

    @Test
    void chainOfTrustedProxiesIsSkippedFromTheRight() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1, 10.0.0.5, 10.0.0.6");

        // client -> 10.0.0.6 -> 10.0.0.5 -> 127.0.0.1 (each proxy appends its peer)
        assertThat(resolver.resolve(request("127.0.0.1", "6.6.6.6, 198.51.100.4, 10.0.0.6, 10.0.0.5")))
                .isEqualTo("198.51.100.4");
    }

    @Test
    void chainOfOnlyTrustedHopsUsesTheOutermostOne() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1, 10.0.0.5");

        assertThat(resolver.resolve(request("127.0.0.1", "10.0.0.5"))).isEqualTo("10.0.0.5");
    }

    @Test
    void emptyHopStopsTheWalkAtTheNearestTrustedAddress() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1, 10.0.0.5");

        assertThat(resolver.resolve(request("127.0.0.1", "203.0.113.7, , 10.0.0.5"))).isEqualTo("10.0.0.5");
        assertThat(resolver.resolve(request("127.0.0.1", "203.0.113.7, "))).isEqualTo("127.0.0.1");
    }

    @Test
    void multipleHeaderLinesAreReadAsOneChain() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeaders("X-Forwarded-For"))
                .thenReturn(Collections.enumeration(List.of("9.9.9.9", "198.51.100.4")));
        when(request.getHeader("X-Forwarded-For")).thenReturn("9.9.9.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.4");
    }

    @Test
    void untrustedPeerCannotSpoofAddressWithForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1,::1");
        HttpServletRequest request = request("198.51.100.9", "203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void trustedProxyFallsBackWhenForwardedHeaderIsMissingOrBlank() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");

        assertThat(resolver.resolve(request("127.0.0.1", null))).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve(request("127.0.0.1", "   "))).isEqualTo("127.0.0.1");
        // the proxy appended 203.0.113.7; the empty entry left of it was client-supplied
        assertThat(resolver.resolve(request("127.0.0.1", ", 203.0.113.7")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void trustedProxyConfigurationTrimsEntriesAndIgnoresEmptyOnes() {
        ClientIpResolver resolver = new ClientIpResolver(" , 10.0.0.10 , , ::1 ");

        assertThat(resolver.resolve(request("10.0.0.10", "203.0.113.8")))
                .isEqualTo("203.0.113.8");
    }

    @Test
    void emptyTrustedProxyConfigurationNeverTrustsForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(" , ");

        assertThat(resolver.resolve(request("127.0.0.1", "203.0.113.8")))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void missingRemoteAddressUsesStableUnknownBucket() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1");

        assertThat(resolver.resolve(request(null, "203.0.113.8"))).isEqualTo("unknown");
    }

    private static HttpServletRequest request(String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }
}
