package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client identity for rate limiting.
 *
 * <p>{@code X-Forwarded-For} is only read when the direct peer is a trusted proxy, and then from
 * the <em>right</em>: every proxy appends the address it received the request from, so only the
 * right-hand part of the chain was written by proxies we trust. The client itself can put anything
 * on the left. The result is the right-most entry that is not a trusted proxy (TODO ba6a91a6 —
 * the left-most entry let every request pick its own bucket and bypass the limits).
 */
public class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private final Set<String> trustedProxies;

    public ClientIpResolver(String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String fallback = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;

        if (remoteAddr == null || !trustedProxies.contains(remoteAddr)) {
            return fallback;
        }

        List<String> chain = forwardedChain(request);
        // Walk from the peer towards the client. `nearestTrusted` is the last hop we can vouch for.
        String nearestTrusted = fallback;
        for (int i = chain.size() - 1; i >= 0; i--) {
            String hop = chain.get(i).trim();
            if (hop.isEmpty()) {
                // A trusted proxy never appends an empty entry: whatever is left of here is not ours.
                return nearestTrusted;
            }
            if (!trustedProxies.contains(hop)) {
                return hop;
            }
            nearestTrusted = hop;
        }
        return nearestTrusted;
    }

    /**
     * All {@code X-Forwarded-For} values in order. A proxy may add a second header line instead of
     * extending the first, so reading only the first line would again hand the client the choice.
     */
    private static List<String> forwardedChain(HttpServletRequest request) {
        List<String> lines = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        if (headers != null) {
            lines.addAll(Collections.list(headers));
        }
        if (lines.isEmpty()) {
            String single = request.getHeader(FORWARDED_FOR);
            if (single != null) {
                lines.add(single);
            }
        }
        List<String> chain = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            chain.addAll(Arrays.asList(line.split(",", -1)));
        }
        return chain;
    }
}
