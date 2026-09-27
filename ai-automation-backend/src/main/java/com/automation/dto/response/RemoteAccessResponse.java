package com.automation.dto.response;

/**
 * @param inContainer this instance runs in a container — Tailscale has no supported path there
 *                     (no CLI, no secret in the container); the frontend shows a hint instead of the
 *                     enable button. Missing on an older backend (frontend deploys ahead of it), so the
 *                     frontend must treat it as {@code false}/absent, not require it.
 */
public record RemoteAccessResponse(
        boolean enabled,
        String ip,
        String hostname,
        boolean inContainer
) {}
