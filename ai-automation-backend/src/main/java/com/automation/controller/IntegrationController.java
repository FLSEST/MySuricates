package com.automation.controller;

import com.automation.dto.response.IntegrationActionResponse;
import com.automation.dto.response.IntegrationConnectionResponse;
import com.automation.dto.response.IntegrationProviderResponse;
import com.automation.entity.IntegrationConnection;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.security.AllowedOrigins;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationConnectionNames;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Integration providers and their connections. Every endpoint that works on one connection takes
 * the optional query parameter {@code connection} (0b3cbcc3); without it the provider's
 * {@code default} connection is meant, exactly as before named connections existed.
 */
@RequiresModule(ModuleCatalog.INTEGRATIONS)
@RestController
@RequestMapping("/api/v1/integrations")
@Slf4j
public class IntegrationController {

    private final IntegrationRegistry registry;
    private final IntegrationOAuthService oAuthService;
    /** The OAuth popup posts its result only to an origin CORS would let in as well (a8056256). */
    private final AllowedOrigins allowedOrigins;
    private final IntegrationProviderResponses responses;

    public IntegrationController(IntegrationRegistry registry, IntegrationOAuthService oAuthService,
                                 ObjectMapper objectMapper, AllowedOrigins allowedOrigins) {
        this.registry = registry;
        this.oAuthService = oAuthService;
        this.allowedOrigins = allowedOrigins;
        this.responses = new IntegrationProviderResponses(objectMapper);
    }

    /** Lists all providers with connection status, actions and account info; top-level fields describe {@code default}. */
    @GetMapping
    public List<IntegrationProviderResponse> listProviders() {
        Map<String, List<IntegrationConnection>> connections = oAuthService.connectionsByProvider();
        return registry.getAllProviders().stream()
                .map(p -> toProviderResponse(p, connections.getOrDefault(p.getProviderId(), List.of())))
                .toList();
    }

    /** Returns one provider with the same fields as the list. */
    @GetMapping("/{providerId}")
    public IntegrationProviderResponse getProvider(@PathVariable String providerId) {
        return toProviderResponse(registry.getProvider(providerId));
    }

    /** Every stored connection of a provider, {@code default} first. */
    @GetMapping("/{providerId}/connections")
    public List<IntegrationConnectionResponse> listConnections(@PathVariable String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        List<IntegrationConnection> connections = oAuthService.listConnections(providerId);
        boolean defaultConfigured = oAuthService.isDefaultConfigured(providerId, connections);
        return connections.stream()
                .map(c -> responses.connection(provider, c, defaultConfigured))
                .toList();
    }

    /**
     * Returns the OAuth {@code authorizationUrl} for the popup and the {@code callbackOrigin} its result comes from.
     * {@code frontendOrigin} must be the API's own origin or one CORS allows (else 400); no client credentials is a 409.
     */
    @GetMapping("/{providerId}/connect")
    public Map<String, String> getAuthorizationUrl(
            @PathVariable String providerId,
            @RequestParam(required = false) List<String> scopes,
            @RequestParam(required = false) String frontendOrigin,
            @RequestParam(required = false) String connection,
            HttpServletRequest request) {
        String name = IntegrationConnectionNames.normalize(connection);
        String redirectUri = buildCallbackUrl(providerId, request);
        String origin = OAuthCallbackPage.validateOrigin(frontendOrigin, redirectUri, allowedOrigins::isAllowed);
        String authUrl = oAuthService.getAuthorizationUrl(providerId, name, redirectUri, scopes, origin);
        java.net.URI callback = java.net.URI.create(redirectUri);
        return Map.of("authorizationUrl", authUrl,
                "callbackOrigin", callback.getScheme() + "://" + callback.getRawAuthority());
    }

    /**
     * OAuth2 callback endpoint — public (no API key required).
     * Exchanges authorization code for tokens, then returns an HTML page
     * that notifies the parent window via postMessage. The connection name comes from the
     * server-side state, never from this request — the callback URL is the same for all connections.
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

    /**
     * Stores {@code clientId} and {@code clientSecret} encrypted for the connection, creating it if new.
     * Both are required unless the provider needs no credentials; a provider without OAuth flow checks them right away.
     */
    @PostMapping("/{providerId}/credentials")
    public IntegrationProviderResponse saveCredentials(
            @PathVariable String providerId,
            @RequestParam(required = false) String connection,
            @RequestBody Map<String, String> body) {
        IntegrationProvider provider = registry.getProvider(providerId);
        String name = IntegrationConnectionNames.normalize(connection);
        String clientId = body.getOrDefault("clientId", "");
        String clientSecret = body.getOrDefault("clientSecret", "");
        if (clientId == null) clientId = "";
        if (clientSecret == null) clientSecret = "";
        // Providers with optional credentials (e.g. local CLI tools) may be connected with empty values.
        if (provider.requiresCredentials() && (clientId.isBlank() || clientSecret.isBlank())) {
            throw new IllegalArgumentException("clientId and clientSecret are required");
        }
        provider.validateCredentialInput(clientId, clientSecret);
        oAuthService.saveCredentials(providerId, name, clientId, clientSecret);
        return toProviderResponse(provider);
    }

    /** Disconnects: clears the stored tokens and revokes an OAuth token at the provider; client credentials stay (204). */
    @DeleteMapping("/{providerId}/connection")
    public ResponseEntity<Void> disconnect(@PathVariable String providerId,
                                           @RequestParam(required = false) String connection) {
        oAuthService.disconnect(providerId, IntegrationConnectionNames.normalize(connection));
        return ResponseEntity.noContent().build();
    }

    /** Deletes the whole connection including its client credentials and revokes an OAuth token at the provider (204). */
    @DeleteMapping("/{providerId}/credentials")
    public ResponseEntity<Void> deleteCredentials(@PathVariable String providerId,
                                                  @RequestParam(required = false) String connection) {
        oAuthService.deleteCredentials(providerId, IntegrationConnectionNames.normalize(connection));
        return ResponseEntity.noContent().build();
    }

    /** Lists the provider's actions; {@code available} is {@code false} when the connection lacks a required scope. */
    @GetMapping("/{providerId}/actions")
    public List<IntegrationActionResponse> getActions(@PathVariable String providerId,
                                                      @RequestParam(required = false) String connection) {
        IntegrationProvider provider = registry.getProvider(providerId);
        String scopes = oAuthService.getConnection(providerId, IntegrationConnectionNames.normalize(connection))
                .map(IntegrationConnection::getScopes).orElse(null);
        return responses.actions(provider, scopes);
    }

    /** Runs one action with the body as its parameters and returns the provider's result; an unconnected provider is a 409. */
    @PostMapping("/{providerId}/actions/{action}")
    public ResponseEntity<Object> testAction(
            @PathVariable String providerId,
            @PathVariable String action,
            @RequestParam(required = false) String connection,
            @RequestBody(required = false) Map<String, Object> params) {

        IntegrationProvider provider = registry.getProvider(providerId);
        String accessToken = oAuthService.getValidAccessToken(providerId, IntegrationConnectionNames.normalize(connection));
        Object result = provider.executeAction(action, params != null ? params : Map.of(), accessToken);
        return ResponseEntity.ok(result);
    }

    /** Lists the targets a TODO can be exported to (e.g. boards or lists); empty if the provider cannot export or the call fails. */
    @GetMapping("/{providerId}/task-targets")
    public List<TaskExportCapable.TaskTarget> taskTargets(@PathVariable String providerId,
                                                          @RequestParam(required = false) String connection) {
        IntegrationProvider provider = registry.getProvider(providerId);
        if (!(provider instanceof TaskExportCapable tc)) {
            return List.of();
        }
        String name = IntegrationConnectionNames.normalize(connection);
        try {
            return tc.listTargets(oAuthService.getValidAccessToken(providerId, name));
        } catch (Exception e) {
            return List.of();
        }
    }

    private IntegrationProviderResponse toProviderResponse(IntegrationProvider provider) {
        return toProviderResponse(provider, oAuthService.listConnections(provider.getProviderId()));
    }

    private IntegrationProviderResponse toProviderResponse(IntegrationProvider provider,
                                                           List<IntegrationConnection> connections) {
        return responses.provider(provider, connections,
                oAuthService.isDefaultConfigured(provider.getProviderId(), connections));
    }

    /**
     * An action counts as available when it needs no scopes, when the connection's scopes are
     * unknown ({@code null}: fine-grained PAT, provider without scope reporting — we cannot prove
     * a gap), or when every required scope was granted. Advisory only: execution is not blocked.
     */
    static boolean isActionAvailable(IntegrationAction action, String scopes) {
        if (action.requiredScopes().isEmpty() || scopes == null) return true;
        return IntegrationProviderResponses.parseScopes(scopes).containsAll(action.requiredScopes());
    }

    private String buildCallbackUrl(String providerId, HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        String portPart = (port == 80 || port == 443) ? "" : ":" + port;
        return scheme + "://" + host + portPart + "/api/v1/integrations/" + providerId + "/callback";
    }
}
