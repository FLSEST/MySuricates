package com.automation.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * All outbound HTTP of the OAuth2 flow: token exchange, refresh and revocation.
 *
 * <p>Kept out of {@link IntegrationOAuthService} so that the service holds no {@link HttpClient}
 * and the slow calls sit behind one seam.
 *
 * <p>Exception messages carry the provider id and the HTTP status but <b>never</b> the provider's
 * response body: {@code GlobalExceptionHandler} passes an {@link IllegalStateException} message
 * straight to the API client as a 409, and a token endpoint's error body can quote the request
 * (including client secrets) back at us.
 */
@Component
@Slf4j
public class OAuthTokenClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    public OAuthTokenClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Exchange an authorization code for tokens ({@code grant_type=authorization_code}). */
    public JsonNode exchangeCodeForTokens(String providerId, OAuthConfig config, String code,
                                          String clientId, String clientSecret, String redirectUri) {
        String body = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret)
                + "&redirect_uri=" + urlEncode(redirectUri);
        return postForTokens(providerId, config.tokenUrl(), body, "Token exchange failed");
    }

    /** Trade a refresh token for a fresh access token ({@code grant_type=refresh_token}). */
    public JsonNode refreshAccessToken(String providerId, OAuthConfig config, String refreshToken,
                                       String clientId, String clientSecret) {
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        return postForTokens(providerId, config.tokenUrl(), body, "Token refresh failed");
    }

    /**
     * Revoke a token at the provider's RFC 7009 endpoint. Never throws — the local token is
     * cleared either way, and a failed revocation must not fail the disconnect.
     */
    public void revoke(String providerId, OAuthConfig config, String token) {
        String revocationUrl = config == null ? null : config.revocationUrl();
        if (revocationUrl == null) {
            log.info("Provider {} has no token revocation endpoint — skipping revocation", providerId);
            return;
        }
        try {
            HttpResponse<String> response = postForm(revocationUrl, "token=" + urlEncode(token));
            if (response.statusCode() >= 300) {
                log.warn("Token revocation for {} returned HTTP {}", providerId, response.statusCode());
            } else {
                log.info("Revoked access token for provider {}", providerId);
            }
        } catch (Exception e) {
            log.warn("Token revocation failed for {}: {}", providerId, e.getMessage());
        }
    }

    private JsonNode postForTokens(String providerId, String tokenUrl, String body, String failure) {
        HttpResponse<String> response;
        try {
            response = postForm(tokenUrl, body);
        } catch (Exception e) {
            log.error("{} for {}", failure, providerId, e);
            throw new IllegalStateException(failure + " for " + providerId, e);
        }
        if (response.statusCode() != 200) {
            log.error("{} for {}: HTTP {} {}", failure, providerId, response.statusCode(), response.body());
            throw new IllegalStateException(failure + " for " + providerId + " (HTTP " + response.statusCode() + ")");
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("{} for {}: unparseable 200 response", failure, providerId, e);
            throw new IllegalStateException(failure + " for " + providerId + " (malformed response)", e);
        }
    }

    private HttpResponse<String> postForm(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(TIMEOUT)
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
