package com.automation.dto.response;

/**
 * A sync target of the staged setup (Test or Prod, TODO 84a78bb7) as Dev sees it.
 *
 * @param name       {@code test} or {@code prod}
 * @param url        base URL from the startup configuration
 * @param keyPresent whether the key file exists and is not empty
 * @param reachable  whether the target answered its health check
 * @param sealedOnly whether only sealed definitions go there (Prod)
 * @param version    the target's version, {@code null} if it did not answer
 */
public record SyncTargetResponse(
        String name,
        String url,
        boolean keyPresent,
        boolean reachable,
        boolean sealedOnly,
        String version
) {
}
