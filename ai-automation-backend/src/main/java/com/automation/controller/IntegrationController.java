package com.automation.controller;

import com.automation.dto.response.IntegrationActionResponse;
import com.automation.dto.response.IntegrationProviderResponse;
import com.automation.entity.IntegrationConnection;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.AllowedOrigins;
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
import java.util.Set;

@RequiresModule(ModuleCatalog.INTEGRATIONS)
@RestController
@RequestMapping("/api/v1/integrations")
@RequiredArgsConstructor
@Slf4j
public class IntegrationController {

    private final IntegrationRegistry registry;
    private final IntegrationOAuthService oAuthService;
    private final ObjectMapper objectMapper;
    /** The OAuth popup posts its result only to an origin CORS would let in as well (a8056256). */
    private final AllowedOrigins allowedOrigins;

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
            @RequestParam(required = false) List<String> scopes,
            @RequestParam(required = false) String frontendOrigin,
            HttpServletRequest request) {
        String redirectUri = buildCallbackUrl(providerId, request);
        String origin = OAuthCallbackPage.validateOrigin(frontendOrigin, redirectUri, allowedOrigins::isAllowed);
        String authUrl = oAuthService.getAuthorizationUrl(providerId, redirectUri, scopes, origin);
        java.net.URI callback = java.net.URI.create(redirectUri);
        return Map.of("authorizationUrl", authUrl,
                "callbackOrigin", callback.getScheme() + "://" + callback.getRawAuthority());
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
        String callbackOrigin = oAuthService.getCallbackOrigin(state);
        IntegrationConnection connection = oAuthService.handleCallback(code, state, redirectUri);
        log.info("OAuth callback successful for provider: {}", providerId);

        return OAuthCallbackPage.render(connection.getProviderId(), callbackOrigin);
    }

    @PostMapping("/{providerId}/credentials")
    public IntegrationProviderResponse saveCredentials(
            @PathVariable String providerId,
            @RequestBody Map<String, String> body) {
        IntegrationProvider provider = registry.getProvider(providerId);
        String clientId = body.getOrDefault("clientId", "");
        String clientSecret = body.getOrDefault("clientSecret", "");
        if (clientId == null) clientId = "";
        if (clientSecret == null) clientSecret = "";
        // Providers with optional credentials (e.g. local CLI tools) may be connected with empty values.
        if (provider.requiresCredentials() && (clientId.isBlank() || clientSecret.isBlank())) {
            throw new IllegalArgumentException("clientId and clientSecret are required");
        }
        oAuthService.saveCredentials(providerId, clientId, clientSecret);
        return toProviderResponse(provider);
    }

    @DeleteMapping("/{providerId}/connection")
    public ResponseEntity<Void> disconnect(@PathVariable String providerId) {
        oAuthService.disconnect(providerId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{providerId}/credentials")
    public ResponseEntity<Void> deleteCredentials(@PathVariable String providerId) {
        oAuthService.deleteCredentials(providerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{providerId}/actions")
    public List<IntegrationActionResponse> getActions(@PathVariable String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        var connection = oAuthService.getConnection(providerId).orElse(null);
        String scopes = connection != null ? connection.getScopes() : null;
        return provider.getAvailableActions().stream()
                .map(a -> new IntegrationActionResponse(a.name(), a.description(), a.inputSchema(),
                        isActionAvailable(a, scopes)))
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

    @GetMapping("/{providerId}/task-targets")
    public List<com.automation.service.integration.TaskExportCapable.TaskTarget> taskTargets(@PathVariable String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        if (!(provider instanceof com.automation.service.integration.TaskExportCapable tc)) {
            return List.of();
        }
        try {
            return tc.listTargets(oAuthService.getValidAccessToken(providerId));
        } catch (Exception e) {
            return List.of();
        }
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

        String scopes = connection != null ? connection.getScopes() : null;
        Set<String> grantedScopes = parseScopes(scopes);
        var config = provider.getOAuthConfig();

        List<IntegrationActionResponse> actions = provider.getAvailableActions().stream()
                .map(a -> new IntegrationActionResponse(a.name(), a.description(), a.inputSchema(),
                        isActionAvailable(a, scopes)))
                .toList();

        List<IntegrationProviderResponse.ScopeOptionResponse> optionalScopes = config.optionalScopes().stream()
                .map(s -> new IntegrationProviderResponse.ScopeOptionResponse(s, s, grantedScopes.contains(s)))
                .toList();

        return new IntegrationProviderResponse(
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getIcon(),
                provider.getDescription(),
                status,
                oAuthService.isProviderConfigured(provider.getProviderId()),
                provider.requiresCredentials(),
                accountEmail,
                accountName,
                connection != null ? connection.getCreatedAt() : null,
                connection != null ? connection.getScopes() : null,
                optionalScopes,
                actions,
                provider instanceof com.automation.service.integration.TaskExportCapable
        );
    }

    /**
     * An action counts as available when it needs no scopes, when the connection's scopes are
     * unknown ({@code null}: fine-grained PAT, provider without scope reporting — we cannot prove
     * a gap), or when every required scope was granted. Advisory only: execution is not blocked.
     */
    static boolean isActionAvailable(com.automation.service.integration.IntegrationAction action, String scopes) {
        if (action.requiredScopes().isEmpty() || scopes == null) return true;
        return parseScopes(scopes).containsAll(action.requiredScopes());
    }

    private static Set<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of();
        return Set.of(scopes.split(" "));
    }

    private String buildCallbackUrl(String providerId, HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String portPart = (port == 80 || port == 443) ? "" : ":" + port;
        return scheme + "://" + host + portPart + "/api/v1/integrations/" + providerId + "/callback";
    }
}
