package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void trustedProxyUsesFirstForwardedAddress() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1, ::1");
        HttpServletRequest request = request("127.0.0.1", " 203.0.113.7 , 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
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
        assertThat(resolver.resolve(request("127.0.0.1", ", 203.0.113.7")))
                .isEqualTo("127.0.0.1");
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
