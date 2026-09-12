package com.automation.service.integration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds provider authorization URLs without performing network or database operations. */
final class OAuthAuthorizationUrl {
    private OAuthAuthorizationUrl() {}

    static String build(OAuthConfig config, String clientId, String redirectUri,
                        List<String> additionalScopes, String state) {
        List<String> scopes = new ArrayList<>(config.defaultScopes());
        if (additionalScopes != null) {
            additionalScopes.stream().filter(config.optionalScopes()::contains).forEach(scopes::add);
        }
        StringBuilder url = new StringBuilder(config.authorizationUrl())
                .append("?client_id=").append(encode(clientId))
                .append("&redirect_uri=").append(encode(redirectUri))
                .append("&response_type=code")
                .append("&scope=").append(encode(String.join(" ", scopes)))
                .append("&state=").append(encode(state));
        config.extraAuthParams().forEach((key, value) ->
                url.append("&").append(encode(key)).append("=").append(encode(value)));
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
