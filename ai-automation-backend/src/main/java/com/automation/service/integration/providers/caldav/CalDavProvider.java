package com.automation.service.integration.providers.caldav;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CalDAV integration (RFC 4791) for Nextcloud, Radicale, Baïkal, Posteo, iCloud and other servers.
 *
 * <p>Direct auth, no OAuth flow: field 1 is the CalDAV URL, field 2 {@code user:app-password}; the
 * framework stores them as {@code "<url>|<user>:<password>"} and validates them through
 * {@link #fetchAccountInfo}. Only http/https URLs are accepted, every request stays on the origin
 * of that URL ({@link CalDavCredentials#resolve}), and the password never reaches a log or an
 * error message.
 */
@Component
@Slf4j
public class CalDavProvider implements IntegrationProvider {

    private final ObjectMapper objectMapper;
    private final CalDavDiscovery discovery;
    private final CalDavEvents events;

    public CalDavProvider(ObjectMapper objectMapper, CalDavHttpClient http) {
        this.objectMapper = objectMapper;
        this.discovery = new CalDavDiscovery(http);
        this.events = new CalDavEvents(http, discovery);
    }

    @Override
    public String getProviderId() {
        return "caldav";
    }

    @Override
    public String getDisplayName() {
        return "CalDAV";
    }

    @Override
    public String getIcon() {
        return "CalendarDays";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers integrations.providers.caldav.description (DE/EN).
        return "Kalender per CalDAV (Nextcloud, Radicale, Posteo, iCloud …): Termine lesen, anlegen, ändern und löschen";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        // No OAuth: the two credential fields are the CalDAV URL and "user:app-password".
        return new OAuthConfig("", "", List.of(), "CALDAV_URL", "CALDAV_USER_PASSWORD");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    /** Validates URL and login with a PROPFIND on the base URL; {@code null} marks the connection as failed. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        try {
            CalDavCredentials cred = CalDavCredentials.parse(combinedToken);
            CalDavDiscovery.Principal principal = discovery.principal(cred);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", principal.displayName() != null ? principal.displayName() : cred.username());
            info.put("user", cred.username());
            info.put("server", cred.baseUrl().getHost());
            info.put("principal", principal.url().getRawPath());
            return objectMapper.writeValueAsString(info);
        } catch (Exception e) {
            log.warn("CalDAV credential check failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return CalDavActionCatalog.actions();
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        CalDavCredentials cred = CalDavCredentials.parse(accessToken);
        Map<String, Object> p = params != null ? params : Map.of();
        return switch (action) {
            case "list_calendars" -> Map.of("calendars", discovery.calendars(cred));
            case "list_events" -> events.list(cred, p);
            case "create_event" -> events.create(cred, p);
            case "update_event" -> events.update(cred, p);
            case "delete_event" -> events.delete(cred, p);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }
}
