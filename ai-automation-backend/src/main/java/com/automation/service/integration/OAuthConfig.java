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
        List<String> optionalScopes,
        String clientIdEnvVar,
        String clientSecretEnvVar,
        Map<String, String> extraAuthParams
) {
    /** Constructor without optional scopes. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes,
                       String clientIdEnvVar, String clientSecretEnvVar, Map<String, String> extraAuthParams) {
        this(authorizationUrl, tokenUrl, defaultScopes, List.of(), clientIdEnvVar, clientSecretEnvVar, extraAuthParams);
    }

    /** Constructor without optional scopes or extra auth params. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes,
                       String clientIdEnvVar, String clientSecretEnvVar) {
        this(authorizationUrl, tokenUrl, defaultScopes, List.of(), clientIdEnvVar, clientSecretEnvVar, Map.of());
    }
}
