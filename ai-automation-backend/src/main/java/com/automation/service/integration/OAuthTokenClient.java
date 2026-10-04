package com.automation.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * All outbound HTTP of the OAuth2 flow: token exchange, refresh and revocation.
 *
 * <p>Kept out of {@link IntegrationOAuthService} so that the service holds no HTTP client
 * (token endpoints are fixed hosts: {@link OutboundHttp#fixedHost})
 * and the slow calls sit behind one seam.
 *
 * <p>Exception messages carry the provider id and the HTTP status but <b>never</b> the provider's
 * response body: {@code GlobalExceptionHandler} passes an {@link IllegalStateException} message
 * straight to the API client as a 409, and a token endpoint's error body can quote the request
 * (including client secrets) back at us. The body is still logged at WARN for diagnostics, but
 * only through {@link #redactForLog}, which masks {@code client_secret}/{@code refresh_token}/
 * {@code access_token}/{@code code} values (form- and JSON-encoded) and truncates the result.
 */
@Component
@Slf4j
public class OAuthTokenClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_LOG_BODY_LENGTH = 300;
    private static final Pattern SENSITIVE_FORM_PARAM =
            Pattern.compile("\\b(client_secret|refresh_token|access_token|code)=[^&\"\\s]*");
    private static final Pattern SENSITIVE_JSON_FIELD =
            Pattern.compile("\"(client_secret|refresh_token|access_token|code)\"\\s*:\\s*\"[^\"]*\"");

    private final ObjectMapper objectMapper;

    private final OutboundClient httpClient = OutboundHttp.fixedHost(TIMEOUT);

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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Token revocation interrupted for {}: {}", providerId, e.getMessage());
        } catch (Exception e) {
            log.warn("Token revocation failed for {}: {}", providerId, e.getMessage());
        }
    }

    private JsonNode postForTokens(String providerId, String tokenUrl, String body, String failure) {
        HttpResponse<String> response;
        try {
            response = postForm(tokenUrl, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} for {}", failure, providerId, e);
            throw new IllegalStateException(failure + " for " + providerId, e);
        } catch (Exception e) {
            log.error("{} for {}", failure, providerId, e);
            throw new IllegalStateException(failure + " for " + providerId, e);
        }
        if (response.statusCode() != 200) {
            log.warn("{} for {}: HTTP {} {}", failure, providerId, response.statusCode(), redactForLog(response.body()));
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

    /**
     * Masks OAuth secret/token values ({@code key=value} form params and {@code "key":"value"}
     * JSON fields) in a provider response body before it is logged, then truncates the result to
     * {@value #MAX_LOG_BODY_LENGTH} characters. Null-safe.
     */
    static String redactForLog(String body) {
        if (body == null) return null;
        String redacted = SENSITIVE_FORM_PARAM.matcher(body).replaceAll("$1=***");
        redacted = SENSITIVE_JSON_FIELD.matcher(redacted).replaceAll("\"$1\":\"***\"");
        return redacted.length() > MAX_LOG_BODY_LENGTH ? redacted.substring(0, MAX_LOG_BODY_LENGTH) : redacted;
    }
}
