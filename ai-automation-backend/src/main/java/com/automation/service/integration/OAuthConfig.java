package com.automation.service.integration;

import java.util.List;
import java.util.Map;

/**
 * OAuth2 configuration for an integration provider.
 *
 * <p>{@code revocationUrl} is the RFC 7009 token-revocation endpoint. It is {@code null} for
 * providers that publish none (Atlassian 3LO, for example) — {@link OAuthTokenClient#revoke}
 * then skips the call instead of posting the token to a foreign provider's endpoint.
 */
public record OAuthConfig(
        String authorizationUrl,
        String tokenUrl,
        List<String> defaultScopes,
        List<String> optionalScopes,
        String clientIdEnvVar,
        String clientSecretEnvVar,
        Map<String, String> extraAuthParams,
        String revocationUrl
) {
    public static final String GOOGLE_REVOCATION_URL = "https://oauth2.googleapis.com/revoke";

    /** Constructor without a revocation endpoint. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes, List<String> optionalScopes,
                       String clientIdEnvVar, String clientSecretEnvVar, Map<String, String> extraAuthParams) {
        this(authorizationUrl, tokenUrl, defaultScopes, optionalScopes, clientIdEnvVar, clientSecretEnvVar, extraAuthParams, null);
    }

    /** Constructor without optional scopes. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes,
                       String clientIdEnvVar, String clientSecretEnvVar, Map<String, String> extraAuthParams) {
        this(authorizationUrl, tokenUrl, defaultScopes, List.of(), clientIdEnvVar, clientSecretEnvVar, extraAuthParams, null);
    }

    /** Constructor without optional scopes or extra auth params. */
    public OAuthConfig(String authorizationUrl, String tokenUrl, List<String> defaultScopes,
                       String clientIdEnvVar, String clientSecretEnvVar) {
        this(authorizationUrl, tokenUrl, defaultScopes, List.of(), clientIdEnvVar, clientSecretEnvVar, Map.of(), null);
    }

    /** Copy with the given revocation endpoint — keeps provider configs to one constructor call. */
    public OAuthConfig withRevocationUrl(String revocationUrl) {
        return new OAuthConfig(authorizationUrl, tokenUrl, defaultScopes, optionalScopes,
                clientIdEnvVar, clientSecretEnvVar, extraAuthParams, revocationUrl);
    }
}
