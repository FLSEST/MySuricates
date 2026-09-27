package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Shared target check for integrations that fetch a URL the workflow author enters (iCal feed,
 * RSS/Atom feed, http-api): the host is resolved and every address it resolves to must be public.
 * Refused are loopback ({@code 127/8}, {@code ::1}), unspecified ({@code 0.0.0.0/8}, {@code ::}),
 * private networks (RFC 1918, {@code fc00::/7}, deprecated {@code fec0::/10}), shared address space
 * ({@code 100.64/10}, e.g. Tailscale), link-local ({@code 169.254/16} with the cloud metadata
 * endpoint, {@code fe80::/10}), multicast and broadcast — also when wrapped in an IPv6 address
 * (IPv4-mapped, IPv4-compatible, NAT64 {@code 64:ff9b::/96}).
 *
 * <p>Checking the resolved addresses (not the host name) catches a public name that points at an
 * internal address. Callers check every redirect hop the same way before following it. What stays
 * is the gap between this lookup and the HTTP client's own: a name whose DNS answer changes within
 * that window (DNS rebinding with a very short TTL) is not caught.
 *
 * <p>Each caller names the hardening flag that switches its check (startup configuration only, read
 * per call): {@link ModuleCatalog#PRIVATE_NETWORK_GUARD} for the feeds (default on) and
 * {@link ModuleCatalog#PRIVATE_NETWORK_GUARD_HTTP_API} for http-api (default off — services in the
 * own network are a common http-api target). Turned off, local targets are reachable on purpose.
 */
@Component
public class OutboundTargetGuard {

    /** Name → addresses; {@link InetAddress#getAllByName} in production, a fixed table in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final ModuleRegistry modules;
    private final Resolver resolver;

    @Autowired
    public OutboundTargetGuard(ModuleRegistry modules) {
        this(modules, InetAddress::getAllByName);
    }

    public OutboundTargetGuard(ModuleRegistry modules, Resolver resolver) {
        this.modules = modules;
        this.resolver = resolver;
    }

    public boolean enabled(ModuleCatalog flag) {
        return modules.isEnabled(flag);
    }

    /**
     * Throws {@link IllegalArgumentException} if {@code uri}'s host is, or resolves to, a local or
     * private address; {@link IllegalStateException} if it cannot be resolved. No-op while
     * {@code flag} is off.
     */
    public void check(URI uri, ModuleCatalog flag) {
        if (!enabled(flag)) return;
        String host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL has no host");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Host " + host + " could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalStateException("Host " + host + " could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                String ip = address.getHostAddress();
                throw new IllegalArgumentException("Target " + host + (ip.equals(host) ? "" : " (" + ip + ")")
                        + " is a local or private network address — integrations only fetch from public "
                        + "addresses. To allow local sources, set " + flag.property() + "=false and restart.");
            }
        }
    }

    /** Loopback, unspecified, private, shared, link-local, multicast or broadcast — IPv4 or IPv6. */
    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) return isInternalIpv4(b);
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true; // fc00::/7 unique local
            byte[] embedded = embeddedIpv4(b);
            if (embedded != null) {
                try {
                    return isInternal(InetAddress.getByAddress(embedded));
                } catch (UnknownHostException e) {
                    return true; // cannot happen for 4 bytes; refuse rather than guess
                }
            }
        }
        return false;
    }

    private static boolean isInternalIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        if (first == 0) return true;                                   // 0.0.0.0/8 "this network"
        if (first == 100 && (second & 0xc0) == 64) return true;        // 100.64.0.0/10 shared (CGNAT, Tailscale)
        return Arrays.equals(b, new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}); // broadcast
    }

    /** The IPv4 address inside an IPv4-mapped, IPv4-compatible or NAT64 ({@code 64:ff9b::/96}) IPv6 address. */
    private static byte[] embeddedIpv4(byte[] b) {
        boolean zero10 = true;
        for (int i = 0; i < 10; i++) zero10 &= b[i] == 0;
        boolean mapped = zero10 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = zero10 && b[10] == 0 && b[11] == 0;
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b;
        for (int i = 4; nat64 && i < 12; i++) nat64 = b[i] == 0;
        return mapped || compatible || nat64 ? Arrays.copyOfRange(b, 12, 16) : null;
    }
}
