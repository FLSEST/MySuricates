package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;

import java.util.List;
import java.util.Map;

/**
 * Interface for external service integrations (Google Calendar, Slack, etc.).
 * Implementations are auto-discovered via Spring @Component.
 */
public interface IntegrationProvider {

    /** Unique provider identifier, e.g. "google-calendar" */
    String getProviderId();

    /** Display name, e.g. "Google Calendar" */
    String getDisplayName();

    /** Lucide icon name for the UI */
    String getIcon();

    /** Short description of what this integration does */
    String getDescription();

    /** OAuth2 configuration for this provider */
    OAuthConfig getOAuthConfig();

    /** Available actions this provider supports */
    List<IntegrationAction> getAvailableActions();

    /** Execute an action against the external API */
    Object executeAction(String action, Map<String, Object> params, String accessToken);

    /** Fetch account info after successful OAuth. Override to provide provider-specific user info. */
    default String fetchAccountInfo(String accessToken) {
        return null;
    }

    /**
     * Effective scopes of a credential-based connection (PAT/API key), space-separated, or
     * {@code null} when the provider cannot tell (e.g. GitHub fine-grained tokens send no scope
     * header). {@code null} means "unknown" — callers must not treat it as "no scopes".
     */
    default String fetchGrantedScopes(String accessToken) {
        return null;
    }

    /**
     * How far this provider has been checked against the real service: VERIFIED when it is listed
     * in {@link VerifiedProviders}, otherwise SIMULATED — a new provider is marked honestly without
     * any extra step.
     */
    default IntegrationVerification getVerification() {
        return getVerifiedAgainst() != null ? IntegrationVerification.VERIFIED : IntegrationVerification.SIMULATED;
    }

    /** What a VERIFIED provider was checked against (from {@link VerifiedProviders}); {@code null} if simulated. */
    default String getVerifiedAgainst() {
        return VerifiedProviders.checkedAgainst(getProviderId());
    }

    /** Whether this provider uses the OAuth2 authorization code flow. False = direct API key/token auth. */
    default boolean requiresOAuthFlow() {
        return true;
    }

    /**
     * Module this provider needs on top of {@code integrations}, or {@code null}. While it is off,
     * {@link IntegrationRegistry} treats the provider as absent — checked per call, so a switch at
     * runtime takes effect at once.
     */
    default ModuleCatalog requiredModule() {
        return null;
    }

    /**
     * Checks the two credential fields before they are stored; throw {@link IllegalArgumentException}
     * (→ 400) for input the provider can never use. Default: accept everything.
     */
    default void validateCredentialInput(String clientId, String clientSecret) {
    }

    /**
     * Whether connecting requires non-empty clientId/clientSecret. Local providers (e.g. a CLI tool
     * with optional config paths) may return false so the user can connect without entering credentials.
     */
    default boolean requiresCredentials() {
        return true;
    }

    /**
     * Whether the integration block and the action endpoint work without any stored connection:
     * with none, {@code IntegrationOAuthService.getValidAccessToken} hands this provider a
     * {@code null} token and it runs on its defaults (public API, feed URL per action, host CLI
     * defaults). Such a provider is usable without setup, and the integrations page says so instead
     * of "Setup required" (TODO cdccc1e5). Implies {@link #requiresCredentials()} {@code == false};
     * a provider whose optional credentials still carry something it cannot run without (the base
     * URL of the HTTP API) stays {@code false}. A stored connection that is not connected still
     * blocks — the connect check (Taskwarrior: is {@code task} installed?) keeps its say.
     */
    default boolean connectionOptional() {
        return false;
    }
}
