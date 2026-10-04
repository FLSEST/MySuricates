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
import java.util.List;
import java.util.function.Predicate;

/**
 * Shared target check for integrations that fetch a URL someone entered (iCal feed, RSS/Atom feed,
 * http-api, the base URL of an own service): the host is resolved and every address it resolves to
 * must be public. Callers do not use it directly — {@link OutboundHttp} hands out the clients and
 * runs this check for every request whose {@link OutboundTarget} is guarded.
 * Refused are loopback ({@code 127/8}, {@code ::1}), unspecified ({@code 0.0.0.0/8}, {@code ::}),
 * private networks (RFC 1918, {@code fc00::/7}, deprecated {@code fec0::/10}), shared address space
 * ({@code 100.64/10}, e.g. Tailscale), link-local ({@code 169.254/16} with the cloud metadata
 * endpoint, {@code fe80::/10}), multicast and broadcast — also when wrapped in an IPv6 address
 * (IPv4-mapped, IPv4-compatible, NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16}, Teredo
 * {@code 2001::/32}).
 *
 * <p>Checking the resolved addresses (not the host name) catches a public name that points at an
 * internal address. Every redirect hop goes through the same check, because the clients never follow
 * redirects themselves. {@link #checkedAddress} returns the address the check accepted, so that
 * {@link OutboundClient} can connect to exactly that address (no second lookup an attacker could
 * rebind in between).
 *
 * <p>The hardening flag that switches a check comes from the target kind (startup configuration only,
 * read per call): {@link ModuleCatalog#PRIVATE_NETWORK_GUARD} for public sources (default on),
 * {@link ModuleCatalog#PRIVATE_NETWORK_GUARD_OWN_SERVICES} for own services and
 * {@link ModuleCatalog#PRIVATE_NETWORK_GUARD_HTTP_API} for http-api (both default off — services in
 * the own network are a common target there). Turned off, local targets are reachable on purpose.
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
    private final Predicate<InetAddress> internal;

    @Autowired
    public OutboundTargetGuard(ModuleRegistry modules) {
        this(modules, InetAddress::getAllByName);
    }

    public OutboundTargetGuard(ModuleRegistry modules, Resolver resolver) {
        this(modules, resolver, OutboundTargetGuard::isInternal);
    }

    /**
     * For tests that need a loopback server to count as public (e.g. to show that a request goes to
     * the checked address): {@code internal} replaces the address classification.
     */
    public OutboundTargetGuard(ModuleRegistry modules, Resolver resolver, Predicate<InetAddress> internal) {
        this.modules = modules;
        this.resolver = resolver;
        this.internal = internal;
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
        checkedAddress(uri, flag);
    }

    /**
     * Same check as {@link #check}, returning the first address the host resolved to (all of them
     * passed) — the address a caller should connect to. {@code null} while {@code flag} is off.
     */
    public InetAddress checkedAddress(URI uri, ModuleCatalog flag) {
        if (!enabled(flag)) return null;
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
            if (internal.test(address)) {
                String ip = address.getHostAddress();
                throw new IllegalArgumentException("Target " + host + (ip.equals(host) ? "" : " (" + ip + ")")
                        + " is a local or private network address — integrations only fetch from public "
                        + "addresses. To allow local sources, set " + flag.property() + "=false and restart.");
            }
        }
        return addresses[0];
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
            for (byte[] embedded : embeddedIpv4(b)) {
                try {
                    if (isInternal(InetAddress.getByAddress(embedded))) return true;
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

    /**
     * The IPv4 addresses inside an IPv6 address: IPv4-mapped, IPv4-compatible and NAT64
     * ({@code 64:ff9b::/96}) carry it in the last four bytes, 6to4 ({@code 2002::/16}) right after the
     * prefix, Teredo ({@code 2001:0::/32}) carries the server in bytes 4–7 and the client
     * bit-inverted in the last four bytes — both are checked.
     */
    private static List<byte[]> embeddedIpv4(byte[] b) {
        boolean zero10 = true;
        for (int i = 0; i < 10; i++) zero10 &= b[i] == 0;
        boolean mapped = zero10 && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = zero10 && b[10] == 0 && b[11] == 0;
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b;
        for (int i = 4; nat64 && i < 12; i++) nat64 = b[i] == 0;
        if (mapped || compatible || nat64) return List.of(Arrays.copyOfRange(b, 12, 16));
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) return List.of(Arrays.copyOfRange(b, 2, 6));
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
            byte[] client = Arrays.copyOfRange(b, 12, 16);
            for (int i = 0; i < 4; i++) client[i] = (byte) ~client[i];
            return List.of(Arrays.copyOfRange(b, 4, 8), client);
        }
        return List.of();
    }
}
