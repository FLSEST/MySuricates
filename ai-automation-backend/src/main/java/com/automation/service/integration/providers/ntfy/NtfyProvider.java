package com.automation.service.integration.providers.ntfy;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.providers.ntfy.NtfyParams.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ntfy integration provider — sends push notifications through a ntfy server (ntfy.sh or self-hosted).
 *
 * <p>No OAuth flow: the two credential fields are the server URL and an optional access token,
 * stored by the framework as {@code "serverUrl|token"}. Both may stay empty — an empty server means
 * {@code https://ntfy.sh}, no token means anonymous publishing without an Authorization header.
 * Without any saved connection the provider publishes anonymously to ntfy.sh.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NtfyProvider implements IntegrationProvider {

    private final ObjectMapper objectMapper;
    private final NtfyHttpClient http;

    @Override
    public String getProviderId() {
        return "ntfy";
    }

    @Override
    public String getDisplayName() {
        return "ntfy";
    }

    @Override
    public String getIcon() {
        return "Bell";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.ntfy.description (DE/EN).
        return "Push-Benachrichtigungen über ntfy senden (ntfy.sh oder eigener Server)";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // No OAuth; the env-var slots name the two direct-auth fields (server URL, access token).
        return new OAuthConfig("", "", List.of(), "NTFY_SERVER_URL", "NTFY_ACCESS_TOKEN");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public boolean requiresCredentials() {
        // Server URL and token are both optional (ntfy.sh, anonymous).
        return false;
    }

    /** Runs without a stored connection (TODO cdccc1e5) — see {@link IntegrationProvider#connectionOptional()}. */
    @Override
    public boolean connectionOptional() {
        return true;
    }

    /** With a token: {@code GET /v1/account} (validates the token); without: {@code GET /v1/health}. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            Connection connection = NtfyParams.parseConnection(combinedToken);
            String host = URI.create(connection.baseUrl()).getHost();
            Map<String, Object> info = new LinkedHashMap<>();
            if (connection.hasToken()) {
                Map<String, Object> account = http.get(connection.baseUrl() + "/v1/account", connection.token());
                Object username = account.get("username");
                String user = username != null ? username.toString() : "?";
                info.put("name", user + " @ " + host);
                info.put("username", user);
            } else {
                Map<String, Object> health = http.get(connection.baseUrl() + "/v1/health", null);
                if (!Boolean.TRUE.equals(health.get("healthy"))) {
                    throw new IllegalStateException("ntfy server reports unhealthy: " + health);
                }
                info.put("name", "anonym @ " + host);
            }
            info.put("server", connection.baseUrl());
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            log.warn("Failed to validate ntfy connection: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(new IntegrationAction("publish", "Eine Push-Benachrichtigung an ein Topic senden",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "topic", Map.of("type", "string", "description", "Topic-Name (Buchstaben, Ziffern, - und _, max. 64 Zeichen)"),
                                "message", Map.of("type", "string", "description", "Nachrichtentext"),
                                "title", Map.of("type", "string", "description", "Titel (optional)"),
                                "priority", Map.of("type", "string", "description", "Priorität 1-5 oder min, low, default, high, max"),
                                "tags", Map.of("type", "string", "description", "Tags/Emoji-Kürzel (kommagetrennt)"),
                                "click", Map.of("type", "string", "description", "URL, die beim Antippen geöffnet wird"),
                                "markdown", Map.of("type", "boolean", "default", false, "description", "Nachricht als Markdown darstellen")
                        ),
                        "required", List.of("topic", "message")
                )));
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        if (!"publish".equals(action)) throw new IllegalArgumentException("Unknown action: " + action);
        Connection connection = NtfyParams.parseConnection(accessToken);
        Map<String, Object> body = NtfyParams.publishBody(params);
        // JSON publishing goes to the server root; the topic travels in the body.
        return http.postJson(connection.baseUrl() + "/", body, connection.token());
    }
}
