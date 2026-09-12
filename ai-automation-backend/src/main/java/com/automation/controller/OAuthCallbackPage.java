package com.automation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

/** OAuth popup response: untrusted strings are data attributes, never JavaScript source. */
final class OAuthCallbackPage {
    private OAuthCallbackPage() {}

    static String validateOrigin(String origin, String redirectUri, String allowedOrigins) {
        URI callback = URI.create(redirectUri);
        String sameOrigin = callback.getScheme() + "://" + callback.getRawAuthority();
        if (origin == null) return sameOrigin;
        URI candidate = URI.create(origin);
        if (!("http".equals(candidate.getScheme()) || "https".equals(candidate.getScheme()))
                || candidate.getHost() == null || candidate.getRawUserInfo() != null
                || candidate.getRawQuery() != null || candidate.getRawFragment() != null
                || !candidate.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("Invalid frontend origin");
        }
        if (sameOrigin.equals(origin)) return origin;
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        if (cors.checkOrigin(origin) == null) throw new IllegalArgumentException("Frontend origin is not allowed");
        return origin;
    }

    static ResponseEntity<String> render(String providerId, String origin) {
        String nonce = UUID.randomUUID().toString();
        String html = """
                <!DOCTYPE html>
                <html><body data-provider="%s" data-origin="%s">
                <script nonce="%s">
                    if (window.opener) {
                        window.opener.postMessage({
                            type: 'oauth-callback',
                            provider: document.body.dataset.provider,
                            status: 'connected'
                        }, document.body.dataset.origin || window.location.origin);
                        window.close();
                    }
                </script>
                <p>Verbindung hergestellt. Dieses Fenster schließt sich automatisch.</p>
                </body></html>
                """.formatted(HtmlUtils.htmlEscape(providerId),
                        HtmlUtils.htmlEscape(origin == null ? "" : origin), nonce);
        return ResponseEntity.ok()
                .header("Content-Type", "text/html;charset=UTF-8")
                .header("Cache-Control", "no-store")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce
                        + "'; base-uri 'none'; frame-ancestors 'none'")
                .body(html);
    }
}
