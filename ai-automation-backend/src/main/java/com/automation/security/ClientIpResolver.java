package com.automation.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client identity for rate limiting.
 *
 * <p>{@code X-Forwarded-For} is only read when the direct peer is a trusted proxy, and then from
 * the <em>right</em>: every proxy appends the address it received the request from, so only the
 * right-hand part of the chain was written by proxies we trust. The client itself can put anything
 * on the left. The result is the right-most entry that is not a trusted proxy (TODO ba6a91a6 —
 * the left-most entry let every request pick its own bucket and bypass the limits).
 *
 * <p>Addresses are compared in canonical form: a container reports the IPv6 loopback as
 * {@code 0:0:0:0:0:0:0:1}, the configuration says {@code ::1} (TODO 854490e6). Only IP literals are
 * normalized — a host name is never resolved, it stays a plain string.
 */
public class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final Pattern IPV4_LITERAL =
            Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private final Set<String> trustedProxies;

    public ClientIpResolver(String trustedProxies) {
        this.trustedProxies = Arrays.stream(trustedProxies.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(ClientIpResolver::canonical)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String fallback = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;

        if (remoteAddr == null || !isTrusted(remoteAddr)) {
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
            if (!isTrusted(hop)) {
                return hop;
            }
            nearestTrusted = hop;
        }
        return nearestTrusted;
    }

    private boolean isTrusted(String address) {
        return trustedProxies.contains(canonical(address.trim()));
    }

    /**
     * The canonical text of an IP literal ({@code ::1} and {@code 0:0:0:0:0:0:0:1} both become the
     * latter, {@code ::ffff:127.0.0.1} becomes {@code 127.0.0.1}); anything else unchanged.
     * {@link InetAddress#getByName} resolves names via DNS, so it only sees strings that are
     * literals by shape: a strict dotted quad, or text with a colon — passed in brackets, which makes
     * the JDK insist on an IPv6 literal and fail instead of looking up e.g. {@code host:80}.
     */
    static String canonical(String address) {
        if (!IPV4_LITERAL.matcher(address).matches() && address.indexOf(':') < 0) {
            return address;
        }
        try {
            String literal = address.indexOf(':') >= 0 ? "[" + address + "]" : address;
            return InetAddress.getByName(literal).getHostAddress();
        } catch (UnknownHostException | SecurityException e) {
            return address;
        }
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
