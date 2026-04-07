package com.automation.service.integration;

import java.util.List;

/**
 * OAuth2 configuration for an integration provider.
 */
public record OAuthConfig(
        String authorizationUrl,
        String tokenUrl,
        List<String> defaultScopes,
        String clientIdEnvVar,
        String clientSecretEnvVar
) {}
