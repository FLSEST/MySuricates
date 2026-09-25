package com.automation.service.integration.providers.feed;

import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.providers.feed.FeedParser.FeedEntry;
import com.automation.service.integration.providers.feed.FeedParser.ParsedFeed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RSS/Atom integration provider — reads RSS 2.0, Atom 1.0 and RSS 1.0 (RDF) feeds.
 *
 * <p>No credentials at all ({@link #requiresCredentials()} is {@code false}); connecting just marks
 * the integration as set up. Entries come back newest first with an ISO-8601 UTC date, a plain-text
 * summary (HTML removed, cut to {@value FeedParser#SUMMARY_LENGTH} characters), author and id/guid.
 */
@Component
@RequiredArgsConstructor
public class FeedProvider implements IntegrationProvider {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 200;

    private final FeedFetcher fetcher;

    @Override
    public String getProviderId() {
        return "feed";
    }

    @Override
    public String getDisplayName() {
        return "RSS/Atom";
    }

    @Override
    public String getIcon() {
        return "Rss";
    }

    @Override
    public String getDescription() {
        // Fallback (German); the UI prefers i18n key integrations.providers.feed.description (DE/EN).
        return "RSS- und Atom-Feeds lesen: neue Einträge mit Titel, Link, Datum und Zusammenfassung";
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

    /** Nothing to validate — a static marker so saving (empty) credentials yields CONNECTED. */
    @Override
    public String fetchAccountInfo(String accessToken) {
        return "{\"name\":\"RSS/Atom\"}";
    }

    @Override
    public List<IntegrationAction> getAvailableActions() {
        return List.of(new IntegrationAction("fetch_feed", "Einträge eines RSS- oder Atom-Feeds abrufen",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "Feed-URL (http/https)"),
                                "since", Map.of("type", "string", "description",
                                        "Nur Einträge ab diesem Zeitpunkt (ISO 8601); Einträge ohne Datum entfallen dann"),
                                "limit", Map.of("type", "integer", "default", DEFAULT_LIMIT,
                                        "description", "Max. Anzahl Einträge (1-" + MAX_LIMIT + "), neueste zuerst")
                        ),
                        "required", List.of("url")
                )));
    }

    @Override
    public Object executeAction(String action, Map<String, Object> params, String accessToken) {
        if (!"fetch_feed".equals(action)) throw new IllegalArgumentException("Unknown action: " + action);
        Object url = params.get("url");
        FeedFetcher.requireHttpUri(url == null ? null : url.toString());
        Instant since = since(params.get("since"));
        int limit = limit(params.get("limit"));
        ParsedFeed feed = FeedParser.parse(fetcher.fetch(url.toString()));
        return result(feed, since, limit);
    }

    static Map<String, Object> result(ParsedFeed feed, Instant since, int limit) {
        List<FeedEntry> entries = new ArrayList<>(feed.entries());
        if (since != null) entries.removeIf(e -> e.published() == null || e.published().isBefore(since));
        entries.sort(Comparator.comparing(FeedEntry::published, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> items = entries.stream().limit(limit).map(FeedProvider::item).toList();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", feed.title());
        meta.put("link", feed.link());
        meta.put("format", feed.format());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("feed", meta);
        result.put("items", items);
        result.put("count", items.size());
        result.put("totalInFeed", feed.entries().size());
        return result;
    }

    private static Map<String, Object> item(FeedEntry e) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", e.title());
        item.put("link", e.link());
        item.put("published", e.published() != null ? e.published().toString() : null);
        item.put("summary", e.summary());
        item.put("author", e.author());
        item.put("id", e.id());
        return item;
    }

    static Instant since(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        Instant since = FeedDates.parse(value.toString());
        if (since == null) throw new IllegalArgumentException("Parameter 'since' is not a valid date: " + value);
        return since;
    }

    static int limit(Object value) {
        int limit;
        if (value == null || value.toString().isBlank()) return DEFAULT_LIMIT;
        if (value instanceof Number n) {
            limit = n.intValue();
        } else {
            try {
                limit = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Parameter 'limit' must be a number: " + value);
            }
        }
        if (limit < 1) throw new IllegalArgumentException("Parameter 'limit' must be at least 1");
        return Math.min(limit, MAX_LIMIT);
    }
}
