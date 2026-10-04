package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;

import java.util.Objects;

/**
 * Where the outbound calls of one HTTP client go — every client from {@link OutboundHttp} declares
 * it, and it decides whether {@link OutboundTargetGuard} checks the target and which hardening flag
 * switches that check.
 *
 * <ul>
 *   <li>{@link Kind#FIXED_HOST} — the host is in the code (GitHub, Google, Slack, OAuth token
 *       endpoints …). No check needed: nobody can point the call elsewhere.</li>
 *   <li>{@link Kind#PUBLIC_SOURCE} — a freely entered URL that is meant to come from the internet
 *       (iCal feed, RSS/Atom). Checked under {@code private-network-guard}, default on.</li>
 *   <li>{@link Kind#OWN_SERVICE} — the freely entered base URL of a self-hosted service (CalDAV,
 *       Gitea, GitLab, ntfy, notification webhooks; http-api with its own flag). The home network
 *       is a legitimate target, so the check is off by default.</li>
 * </ul>
 */
public record OutboundTarget(Kind kind, ModuleCatalog flag) {

    public enum Kind { FIXED_HOST, PUBLIC_SOURCE, OWN_SERVICE }

    public OutboundTarget {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.FIXED_HOST) != (flag == null)) {
            throw new IllegalArgumentException("A fixed host has no guard flag; every other kind needs one");
        }
    }

    public static OutboundTarget fixedHost() {
        return new OutboundTarget(Kind.FIXED_HOST, null);
    }

    public static OutboundTarget publicSource() {
        return new OutboundTarget(Kind.PUBLIC_SOURCE, ModuleCatalog.PRIVATE_NETWORK_GUARD);
    }

    public static OutboundTarget ownService() {
        return new OutboundTarget(Kind.OWN_SERVICE, ModuleCatalog.PRIVATE_NETWORK_GUARD_OWN_SERVICES);
    }

    /** An own service with a switch of its own ({@code private-network-guard-http-api}). */
    public static OutboundTarget ownService(ModuleCatalog flag) {
        return new OutboundTarget(Kind.OWN_SERVICE, Objects.requireNonNull(flag, "flag"));
    }

    public boolean guarded() {
        return flag != null;
    }
}
