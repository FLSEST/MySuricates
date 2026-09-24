package com.automation.service.integration.providers.httpapi;

import java.util.Locale;
import java.util.Set;

/** Header rules of the {@code http-api} provider — shared by the auth header and per-request headers. */
final class HttpApiHeaders {

    /**
     * Hop-by-hop, framing and proxy headers: set by the HTTP client or meaningless end-to-end, and
     * the classic levers for request smuggling. Never accepted from a connection or a block.
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "expect",
            "keep-alive", "te", "trailer", "proxy-authorization", "proxy-connection");

    private HttpApiHeaders() {
    }

    static boolean isForbidden(String name) {
        return FORBIDDEN.contains(name.toLowerCase(Locale.ROOT));
    }

    /** CR, LF, NUL and every other control character except horizontal tab — header injection. */
    static boolean hasIllegalValueChar(String value) {
        return value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f);
    }
}
