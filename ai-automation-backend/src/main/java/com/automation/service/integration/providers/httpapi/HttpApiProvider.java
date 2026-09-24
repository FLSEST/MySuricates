package com.automation.service.integration.providers.httpapi;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * General HTTP API connection (78fae34c): calls a service the user entered — base URL plus an
 * optional auth header — from an {@code integration} block. Several services are several named
 * connections of this one provider ({@code "connection": "weather"}).
 *
 * <p>Belongs to module {@link ModuleCatalog#HTTP_API} (on top of {@code integrations}). The
 * {@link com.automation.service.integration.IntegrationRegistry} hides the provider while the
 * module is off, and {@link #executeAction} checks it again per call.
 *
 * <p>No OAuth: the two credential fields are the connection ({@link HttpApiTarget}), validated
 * when saved. Saving makes no network call; the account shown is {@code host/base-path}.
 */
@Component
@RequiredArgsConstructor
public class HttpApiProvider implements IntegrationProvider {

    static final String PROVIDER_ID = "http-api";
    static final String ACTION_REQUEST = "request";

    private final HttpApiClient client;
    private final ObjectMapper objectMapper;
    private final ModuleRegistry modules;

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayName() {
        return "HTTP API";
    }

    @Override
    public String getIcon() {
        return "Globe";
    }

    @Override
    public String getDescription() {
        return "Beliebige HTTP-Dienste per Basis-URL und optionalem Auth-Header aufrufen — mehrere Dienste als benannte Verbindungen";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // No OAuth and deliberately no env-var fallback: the slots name variables nothing sets.
        return new OAuthConfig("", "", List.of(), "MYSURICATES_HTTP_API_NO_ENV_ID", "MYSURICATES_HTTP_API_NO_ENV_SECRET");
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("method", Map.of("type", "string", "enum", List.copyOf(HttpApiRequest.METHODS), "default", "GET"));
        schema.put("path", Map.of("type", "string", "description", "Relative to the base URL, e.g. 'users/42'; cannot leave it"));
        schema.put("query", Map.of("type", "object", "description", "Query parameters, name → value (a list repeats the name)"));
        schema.put("headers", Map.of("type", "object", "description", "Extra headers; the auth header cannot be overridden"));
        schema.put("body", Map.of("description", "JSON object/array (sent as application/json) or text; not with GET, max 1 MiB"));
        schema.put("timeout_seconds", Map.of("type", "integer", "default", HttpApiRequest.DEFAULT_TIMEOUT_SECONDS,
                "maximum", HttpApiRequest.MAX_TIMEOUT_SECONDS));
        schema.put("fail_on_error", Map.of("type", "boolean", "default", true,
                "description", "HTTP status ≥ 400 fails the step; false returns it as result"));
        return List.of(new IntegrationAction(ACTION_REQUEST,
                "HTTP request to the connection's service → status, content_type, body (JSON parsed or text), truncated",
                schema));
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        if (!modules.isEnabled(ModuleCatalog.HTTP_API)) {
            throw new IllegalStateException(ModuleCatalog.HTTP_API.disabledMessage("Integration provider 'http-api'"));
        }
        if (!ACTION_REQUEST.equals(action)) {
            throw new IllegalArgumentException("Unknown http-api action: " + action + " (available: request)");
        }
        HttpApiTarget target = HttpApiTarget.fromCredential(accessToken);
        return client.send(target, HttpApiRequest.from(target, params, objectMapper));
    }

    @Override
    public void validateCredentialInput(String baseUrl, String authHeader) {
        HttpApiTarget.parse(baseUrl, authHeader);
    }

    /** Account shown in the UI: {@code host/base-path}. No request is sent. */
    @Override
    public String fetchAccountInfo(String accessToken) {
        HttpApiTarget target = HttpApiTarget.fromCredential(accessToken);
        try {
            return objectMapper.writeValueAsString(Map.of("name", target.label()));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    /** The auth header may be empty (public APIs); the base URL is enforced by {@link #validateCredentialInput}. */
    @Override
    public boolean requiresCredentials() {
        return false;
    }

    @Override
    public ModuleCatalog requiredModule() {
        return ModuleCatalog.HTTP_API;
    }
}
