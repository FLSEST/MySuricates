package com.automation.service.integration.providers.icalfeed;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.providers.ical.ICalParser;
import com.automation.service.integration.providers.ical.ICalParser.ICalCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.integration.providers.ical.CalendarParams.*;

/**
 * Reads public iCalendar feeds ({@code .ics} subscriptions: holidays, school terms, club or event
 * calendars). No account and no stored credentials — {@link #requiresCredentials()} is false and
 * the feed URL is an action parameter, so one connection serves any number of feeds.
 */
@Component
@RequiredArgsConstructor
public class ICalFeedProvider implements IntegrationProvider {

    private final ICalFeedFetcher fetcher;

    @Override
    public String getProviderId() {
        return "ical-feed";
    }

    @Override
    public String getDisplayName() {
        return "iCal-Feed";
    }

    @Override
    public String getIcon() {
        return "Rss";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers integrations.providers.ical-feed.description (DE/EN).
        return "Öffentliche Kalender-Abos (.ics) lesen: Feiertage, Ferien, Vereins- und Veranstaltungskalender";
    }

    @Override
    public OAuthConfig getOAuthConfig() {
        return new OAuthConfig("", "", List.of(), "", "");
    }

    @Override
    public boolean requiresOAuthFlow() {
        return false;
    }

    @Override
    public boolean requiresCredentials() {
        return false;
    }

    /** Runs without a stored connection (TODO cdccc1e5) — see {@link IntegrationProvider#connectionOptional()}. */
    @Override
    public boolean connectionOptional() {
        return true;
    }

    /** Nothing to validate — connecting only enables the provider in the UI. */
    @Override
    public String fetchAccountInfo(String combinedToken) {
        return "{\"name\":\"iCal-Feed\"}";
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(new IntegrationAction("list_events",
                "Termine eines öffentlichen iCal-Feeds im Zeitraum abrufen (Serien nicht expandiert)",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "Feed-URL (https://, http:// oder webcal://)"),
                                "from", Map.of("type", "string", "description", "Beginn (ISO 8601 oder Datum; Standard: heute)"),
                                "to", Map.of("type", "string", "description", "Ende (ISO 8601 oder Datum inkl.; Standard: from + 30 Tage)"),
                                "limit", Map.of("type", "integer", "default", 100, "description", "Max. Termine (bis 1000)"),
                                "timeZone", Map.of("type", "string", "description",
                                        "Zeitzone für Datumsangaben, ganztägige und zonenlose Termine (Standard: Server-Zeitzone)")
                        ),
                        "required", List.of("url")
                )));
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        if (!"list_events".equals(action)) throw new IllegalArgumentException("Unknown action: " + action);
        Map<String, Object> p = params != null ? params : Map.of();
        return listEvents(p);
    }

    private Map<String, Object> listEvents(Map<String, Object> params) {
        URI url = ICalFeedFetcher.validate(requireParam(params, "url"));
        ZoneId zone = zone(params, "timeZone", ZoneId.systemDefault());
        Instant from = instant(params, "from", LocalDate.now(zone).atStartOfDay(zone).toInstant(), false, zone);
        Instant to = instant(params, "to", from.plus(Duration.ofDays(30)), true, zone);
        if (!to.isAfter(from)) throw new IllegalArgumentException("'to' must be after 'from'");
        int limit = Math.max(1, Math.min(paramInt(params, "limit", 100), 1000));

        ICalCalendar calendar = ICalParser.parse(fetcher.fetch(url));
        List<Map<String, Object>> events = ICalFeedWindow.select(calendar.events(), from, to, zone);
        boolean hasSeries = events.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("recurring")));

        Map<String, Object> result = new LinkedHashMap<>();
        if (calendar.name() != null) result.put("calendarName", calendar.name());
        result.put("source", url.getHost());
        result.put("from", from.toString());
        result.put("to", to.toString());
        result.put("count", Math.min(events.size(), limit));
        result.put("truncated", events.size() > limit);
        result.put("events", events.size() > limit ? events.subList(0, limit) : events);
        if (hasSeries) result.put("note", ICalFeedWindow.SERIES_NOTE);
        return result;
    }

}
