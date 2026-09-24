package com.automation.service.integration;

import java.util.Map;
import java.util.Set;

/**
 * Ledger of the providers that were checked against the real service, and what against.
 *
 * <p>Every provider not listed here is {@link IntegrationVerification#SIMULATED}: its tests ran
 * against simulated responses only. Adding an entry is the deliberate step after a run against the
 * real service (a local container of the real software counts). {@code IntegrationVerificationGateTest}
 * checks that every key names a shipped provider.
 */
public final class VerifiedProviders {

    private static final Map<String, String> CHECKED_AGAINST = Map.ofEntries(
            Map.entry("google-calendar", "Google Calendar API, eigener Google-Account"),
            Map.entry("google-gmail", "Gmail API, eigener Google-Account"),
            Map.entry("jira", "Jira Cloud (Atlassian 3LO), eigener Account"),
            Map.entry("confluence", "Confluence Cloud (Atlassian 3LO), eigener Account"),
            Map.entry("trello", "Trello REST API, eigener Account"),
            Map.entry("taskwarrior", "Taskwarrior-CLI auf dem Host"),
            Map.entry("github", "GitHub REST API, eigener Account"),
            Map.entry("gitea", "Gitea 1.27.3 und Forgejo 14.0.5, lokaler Container"),
            Map.entry("caldav", "Radicale 3.8.0, lokaler Container"),
            Map.entry("ical-feed", "öffentlicher Feed: Google-Kalender „Feiertage in Deutschland“"),
            Map.entry("ntfy", "ntfy 2.28.0, lokaler Container (anonym und mit Access-Token)"),
            Map.entry("feed", "Öffentliche Feeds: GitHub-Releases (Atom), tagesschau.de und Hacker News (RSS 2.0), debian.org (RSS 1.0/RDF)"),
            Map.entry("open-meteo", "Open-Meteo API (forecast + geocoding), echte Abfrage für Zürich"),
            Map.entry("http-api", "httpbin.org (öffentliche HTTP-Test-API): alle fünf Methoden, Query, JSON-/Text-Body, ")
    );

    private VerifiedProviders() {
    }

    /** What the provider was checked against, or {@code null} when it is only simulated. */
    public static String checkedAgainst(String providerId) {
        return providerId == null ? null : CHECKED_AGAINST.get(providerId);
    }

    /** All provider ids in the ledger. */
    public static Set<String> providerIds() {
        return CHECKED_AGAINST.keySet();
    }
}
