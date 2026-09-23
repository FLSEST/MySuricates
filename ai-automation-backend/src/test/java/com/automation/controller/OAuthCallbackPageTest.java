package com.automation.controller;

import com.automation.security.AllowedOrigins;
import com.automation.service.system.OwnTailnetAddresses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthCallbackPageTest {
    private static final String CALLBACK = "http://localhost:9090/api/v1/integrations/google/callback";
    /** The CORS policy: https://localhost:3000 configured, this instance is dev.tail123.ts.net in its tailnet. */
    private static final Predicate<String> ALLOWED = allowed();

    private static Predicate<String> allowed() {
        OwnTailnetAddresses ownTailnet = mock(OwnTailnetAddresses.class);
        when(ownTailnet.hosts()).thenReturn(Set.of("dev.tail123.ts.net"));
        return new AllowedOrigins("https://localhost:3000", ownTailnet)::isAllowed;
    }

    @Test
    void rendersUntrustedValuesOnlyAsEscapedAttributesAndAllowsOnlyItsNonceScript() {
        String attack = "\"><script>alert('x')</script>&";
        var response = OAuthCallbackPage.render(attack, "https://localhost:3000");
        String html = response.getBody();
        assertThat(html).doesNotContain(attack, "}, '*')");
        assertThat(html).contains("&lt;script&gt;", "data-origin=\"https://localhost:3000\"",
                "provider: document.body.dataset.provider", "document.body.dataset.origin || window.location.origin");
        String nonce = html.split("<script nonce=\"")[1].split("\"")[0];
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("script-src 'nonce-" + nonce + "'").doesNotContain("unsafe-inline");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void permitsSameOriginAndConfiguredFrontendOriginsIncludingSeparateDevPort() {
        assertThat(OAuthCallbackPage.validateOrigin(null, CALLBACK, ALLOWED)).isEqualTo("http://localhost:9090");
        assertThat(OAuthCallbackPage.validateOrigin("http://localhost:9090", CALLBACK, ALLOWED))
                .isEqualTo("http://localhost:9090");
        assertThat(OAuthCallbackPage.validateOrigin("https://localhost:3000", CALLBACK, ALLOWED))
                .isEqualTo("https://localhost:3000");
        assertThat(OAuthCallbackPage.validateOrigin("https://dev.tail123.ts.net:3000", CALLBACK, ALLOWED))
                .isEqualTo("https://dev.tail123.ts.net:3000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "https://evil.example", "https://localhost:3000.evil.example",
            "javascript:alert(1)", "https://user@localhost:3000", "https://localhost:3000/path",
            "https://localhost:3000?x=1", "https://localhost:3000#x",
            // a8056256: a foreign host in the same or another tailnet is no postMessage target
            "https://other.tail123.ts.net:3000", "https://dev.tail999.ts.net:3000"})
    void rejectsNonOriginsAndUntrustedOrigins(String origin) {
        assertThatThrownBy(() -> OAuthCallbackPage.validateOrigin(origin, CALLBACK, ALLOWED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
