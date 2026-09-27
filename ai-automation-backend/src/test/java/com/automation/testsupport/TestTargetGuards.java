package com.automation.testsupport;

import com.automation.modules.ModuleRegistry;
import com.automation.service.integration.OutboundTargetGuard;

import java.net.InetAddress;
import java.util.Map;

/** {@link OutboundTargetGuard} variants for tests that talk to a loopback {@code HttpServer}. */
public final class TestTargetGuards {

    /** An address from TEST-NET-3 (RFC 5737) — public as far as the guard is concerned. */
    public static final String PUBLIC_STAND_IN = "203.0.113.10";

    private TestTargetGuards() {
    }

    /** Both hardening flags off: local targets allowed for feeds and http-api. */
    public static OutboundTargetGuard off() {
        return new OutboundTargetGuard(registry(false));
    }

    /** Both hardening flags on (feeds and http-api), real DNS. */
    public static OutboundTargetGuard on() {
        return new OutboundTargetGuard(registry(true));
    }

    /** Catalog defaults: feeds guarded, http-api not. Real DNS. */
    public static OutboundTargetGuard defaults() {
        return new OutboundTargetGuard(ModuleRegistry.of(Map.of(), Map.of()));
    }

    /**
     * Both flags on, but the literal {@code 127.0.0.1} counts as a public host — lets a test reach its
     * loopback server while every other target (e.g. a redirect hop) gets the real check.
     */
    public static OutboundTargetGuard onWithLoopbackServerAsPublic() {
        return new OutboundTargetGuard(registry(true), host -> "127.0.0.1".equals(host)
                ? new InetAddress[]{InetAddress.getByName(PUBLIC_STAND_IN)}
                : InetAddress.getAllByName(host));
    }

    /** Both address-check flags ({@code private-network-guard}, {@code private-network-guard-http-api}) set to {@code guardOn}. */
    public static ModuleRegistry registry(boolean guardOn) {
        return ModuleRegistry.of(Map.of(), Map.of("private-network-guard", Boolean.toString(guardOn),
                "private-network-guard-http-api", Boolean.toString(guardOn)));
    }
}
