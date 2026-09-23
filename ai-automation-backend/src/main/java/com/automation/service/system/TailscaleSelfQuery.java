package com.automation.service.system;

import java.util.Optional;

/**
 * The two read-only questions this instance asks Tailscale about itself (a8056256): its IPv4 in the
 * tailnet and its status. Nothing here changes Tailscale's state — {@link TailscaleService} owns
 * {@code up}/{@code down}. An interface, so tests answer with a fake instead of a real CLI.
 */
public interface TailscaleSelfQuery {

    /** Stdout of {@code tailscale ip -4}; empty if the CLI is missing, fails or times out. */
    Optional<String> ipv4();

    /** Stdout of {@code tailscale status --json}; empty if the CLI is missing, fails or times out. */
    Optional<String> statusJson();
}
