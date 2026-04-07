package com.automation.controller;

import com.automation.dto.response.IntegrationActionResponse;
import com.automation.dto.response.IntegrationProviderResponse;
import com.automation.entity.IntegrationConnection;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
@Slf4j
public class IntegrationController {

    private final IntegrationRegistry registry;
    private final IntegrationOAuthService oAuthService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<IntegrationProviderResponse> listProviders() {
        return registry.getAllProviders().stream()
                .map(this::toProviderResponse)
                .toList();
    }

    @GetMapping("/{providerId}")
    public IntegrationProviderResponse getProvider(@PathVariable String providerId) {
        return toProviderResponse(registry.getProvider(providerId));
    }

    @GetMapping("/{providerId}/connect")
    public Map<String, String> getAuthorizationUrl(
            @PathVariable String providerId,
            HttpServletRequest request) {
        String redirectUri = buildCallbackUrl(providerId, request);
        String authUrl = oAuthService.getAuthorizationUrl(providerId, redirectUri);
        return Map.of("authorizationUrl", authUrl);
    }

    /**
     * OAuth2 callback endpoint — public (no API key required).
     * Exchanges authorization code for tokens, then returns an HTML page
     * that notifies the parent window via postMessage.
     */
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<String> handleCallback(
            @PathVariable String providerId,
            @RequestParam String code,
            @RequestParam String state,
            HttpServletRequest request) {

        String redirectUri = buildCallbackUrl(providerId, request);
        IntegrationConnection connection = oAuthService.handleCallback(code, state, redirectUri);
        log.info("OAuth callback successful for provider: {}", providerId);

        String html = """
                <!DOCTYPE html>
                <html><body>
                <script>
                    window.opener.postMessage({ type: 'oauth-callback', provider: '%s', status: 'connected' }, '*');
                    window.close();
                </script>
                <p>Verbindung hergestellt. Dieses Fenster schließt sich automatisch.</p>
                </body></html>
                """.formatted(providerId);

        return ResponseEntity.ok()
                .header("Content-Type", "text/html")
                .body(html);
    }

    @PostMapping("/{providerId}/credentials")
    public IntegrationProviderResponse saveCredentials(
            @PathVariable String providerId,
            @RequestBody Map<String, String> body) {
        String clientId = body.get("clientId");
        String clientSecret = body.get("clientSecret");
        if (clientId == null || clientSecret == null || clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientId and clientSecret are required");
        }
        oAuthService.saveCredentials(providerId, clientId, clientSecret);
        return toProviderResponse(registry.getProvider(providerId));
    }

    @DeleteMapping("/{providerId}/connection")
    public ResponseEntity<Void> disconnect(@PathVariable String providerId) {
        oAuthService.disconnect(providerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{providerId}/actions")
    public List<IntegrationActionResponse> getActions(@PathVariable String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        return provider.getAvailableActions().stream()
                .map(a -> new IntegrationActionResponse(a.name(), a.description(), a.inputSchema()))
                .toList();
    }

    @PostMapping("/{providerId}/actions/{action}")
    public ResponseEntity<Object> testAction(
            @PathVariable String providerId,
            @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> params) {

        IntegrationProvider provider = registry.getProvider(providerId);
        String accessToken = oAuthService.getValidAccessToken(providerId);
        Object result = provider.executeAction(action, params != null ? params : Map.of(), accessToken);
        return ResponseEntity.ok(result);
    }

    private IntegrationProviderResponse toProviderResponse(IntegrationProvider provider) {
        var connection = oAuthService.getConnection(provider.getProviderId()).orElse(null);

        String status = connection != null ? connection.getStatus().name() : "DISCONNECTED";
        String accountEmail = null;
        String accountName = null;

        if (connection != null && connection.getAccountInfo() != null) {
            try {
                JsonNode info = objectMapper.readTree(connection.getAccountInfo());
                accountEmail = info.has("email") ? info.get("email").asText() : null;
                accountName = info.has("name") ? info.get("name").asText() : null;
            } catch (Exception e) {
                log.warn("Failed to parse account info for {}", provider.getProviderId());
            }
        }

        List<IntegrationActionResponse> actions = provider.getAvailableActions().stream()
                .map(a -> new IntegrationActionResponse(a.name(), a.description(), a.inputSchema()))
                .toList();

        return new IntegrationProviderResponse(
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getIcon(),
                provider.getDescription(),
                status,
                oAuthService.isProviderConfigured(provider.getProviderId()),
                accountEmail,
                accountName,
                connection != null ? connection.getCreatedAt() : null,
                connection != null ? connection.getScopes() : null,
                actions
        );
    }

    private String buildCallbackUrl(String providerId, HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String portPart = (port == 80 || port == 443) ? "" : ":" + port;
        return scheme + "://" + host + portPart + "/api/v1/integrations/" + providerId + "/callback";
    }
}
