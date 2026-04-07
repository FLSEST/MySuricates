package com.automation.service.integration;

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
}
