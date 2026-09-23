package com.automation.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedOriginHandshakeInterceptorTest {

    private final AllowedOriginHandshakeInterceptor interceptor = new AllowedOriginHandshakeInterceptor(
            new AllowedOrigins(AllowedOrigins.DEFAULT_ORIGINS, () -> Set.of("100.64.0.7", "mine.tail1234.ts.net")));

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    /** A handshake as the backend sees it behind the Vite proxy: Host rewritten, Origin untouched. */
    private boolean handshake(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/terminal/abc");
        request.setServerName("localhost");
        request.setServerPort(9090);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response), null, new HashMap<>());
    }

    @Test
    void foreignTailnet_isRejectedWith403() {
        assertThat(handshake("https://evil.tail0000.ts.net:3000")).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void lookalikeHundredHost_isRejected() {
        assertThat(handshake("http://100.evil.example:3000")).isFalse();
    }

    @Test
    void ownTailscaleAddress_passes() {
        assertThat(handshake("https://mine.tail1234.ts.net:3000")).isTrue();
        assertThat(handshake("http://100.64.0.7:3000")).isTrue();
    }

    @Test
    void localhostDevServer_passes() {
        assertThat(handshake("http://localhost:3000")).isTrue();
    }

    @Test
    void sameOriginOrNoOrigin_passes() {
        assertThat(handshake("http://localhost:9090")).isTrue();
        assertThat(handshake(null)).isTrue();
    }
}
