package com.automation.testsupport;

import com.automation.modules.ModuleRegistry;
import com.automation.service.integration.OutboundClient;
import com.automation.service.integration.OutboundHttp;
import com.automation.service.integration.OutboundTargetGuard;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/** {@link OutboundTargetGuard} variants for tests that talk to a loopback {@code HttpServer}. */
public final class TestTargetGuards {

    /** An address from TEST-NET-3 (RFC 5737) — public as far as the guard is concerned. */
    public static final String PUBLIC_STAND_IN = "203.0.113.10";

    private TestTargetGuards() {
    }

    /** Outbound clients with the catalog defaults: public sources guarded, own services and http-api not. */
    public static OutboundHttp outbound() {
        return new OutboundHttp(defaults());
    }

    /**
     * Outbound clients for unit tests that must never leave the machine: every guarded client (public
     * source, own service, http-api) reaches loopback only. Any other name fails as unresolvable without a
     * DNS lookup, any other IP literal as a non-public address — no request goes out. Fixed-host clients
     * are not guarded; their tests point them at a loopback stub through their test constructors.
     */
    public static OutboundHttp loopbackOnly() {
        return new OutboundHttp(new OutboundTargetGuard(registry(true), host -> {
            if ("localhost".equalsIgnoreCase(host)) return new InetAddress[]{InetAddress.getLoopbackAddress()};
            if (!OutboundClient.isIpLiteral(host)) {
                throw new UnknownHostException(host + ": no DNS in unit tests");
            }
            return new InetAddress[]{InetAddress.getByName(host)};
        }, address -> !address.isLoopbackAddress()));
    }

    /** Outbound clients checked by {@code guard}. */
    public static OutboundHttp outbound(OutboundTargetGuard guard) {
        return new OutboundHttp(guard);
    }

    /** All address-check flags off: local targets allowed for feeds, own services and http-api. */
    public static OutboundTargetGuard off() {
        return new OutboundTargetGuard(registry(false));
    }

    /** All address-check flags on (feeds, own services, http-api), real DNS. */
    public static OutboundTargetGuard on() {
        return new OutboundTargetGuard(registry(true));
    }

    /** Catalog defaults: feeds guarded, own services and http-api not. Real DNS. */
    public static OutboundTargetGuard defaults() {
        return new OutboundTargetGuard(ModuleRegistry.of(Map.of(), Map.of()));
    }

    /**
     * All flags on, but the literal {@code 127.0.0.1} counts as a public host — lets a test reach its
     * loopback server while every other target (e.g. a redirect hop) gets the real check.
     */
    public static OutboundTargetGuard onWithLoopbackServerAsPublic() {
        return new OutboundTargetGuard(registry(true), host -> "127.0.0.1".equals(host)
                ? new InetAddress[]{InetAddress.getByName(PUBLIC_STAND_IN)}
                : InetAddress.getAllByName(host));
    }

    /** All address-check flags ({@code private-network-guard}, {@code -own-services}, {@code -http-api}) set to {@code guardOn}. */
    public static ModuleRegistry registry(boolean guardOn) {
        return ModuleRegistry.of(Map.of(), Map.of("private-network-guard", Boolean.toString(guardOn),
                "private-network-guard-own-services", Boolean.toString(guardOn),
                "private-network-guard-http-api", Boolean.toString(guardOn)));
    }
}
