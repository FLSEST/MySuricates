package com.automation.service.integration;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The one place where integrations get an outbound HTTP client. Each client declares its
 * {@link OutboundTarget} (fixed host, public source, own service); the target check of
 * {@link OutboundTargetGuard}, the redirect rule (never followed by the client, every hop a new
 * checked request) and the connection to the checked address come with the client — a provider
 * cannot forget them. {@code OutboundHttpGateTest} fails the build for any other HTTP client in the
 * backend that is not pinned there with its reason.
 *
 * <p>Not covered on purpose: shell blocks (the owner's code) and the AI CLIs (own tools and rights).
 */
@Component
public class OutboundHttp {

    private final OutboundTargetGuard guard;

    public OutboundHttp(OutboundTargetGuard guard) {
        this.guard = guard;
    }

    /** A client for {@code target}; {@code connectTimeout} {@code null} keeps the JDK default. */
    public OutboundClient client(OutboundTarget target, Duration connectTimeout) {
        return new OutboundClient(target, guard, connectTimeout);
    }

    /**
     * A client for a host that is fixed in the code — needs no guard and therefore no bean, so
     * providers that only call fixed hosts keep their plain constructors.
     */
    public static OutboundClient fixedHost(Duration connectTimeout) {
        return new OutboundClient(OutboundTarget.fixedHost(), null, connectTimeout);
    }
}
