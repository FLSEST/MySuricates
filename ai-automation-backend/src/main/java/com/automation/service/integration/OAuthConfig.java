package com.automation.service.integration;

import java.util.List;
import java.util.Map;

/**
 * OAuth2 configuration for an integration provider.
 */
public record OAuthConfig(
        String authorizationUrl,
        String tokenUrl,
        List<String> defaultScopes,
        String clientIdEnvVar,
        String clientSecretEnvVar,
        Map<String, String> extraAuthParams
) {
    /** Backward-compatible constructor for providers without extra auth params. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes,
                       String clientIdEnvVar, String clientSecretEnvVar) {
        this(authorizationUrl, tokenUrl, defaultScopes, clientIdEnvVar, clientSecretEnvVar, Map.of());
    }
}
