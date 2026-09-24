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

    private static final Map<String, String> CHECKED_AGAINST = Map.of(
            "google-calendar", "Google Calendar API, eigener Google-Account",
            "google-gmail", "Gmail API, eigener Google-Account",
            "jira", "Jira Cloud (Atlassian 3LO), eigener Account",
            "confluence", "Confluence Cloud (Atlassian 3LO), eigener Account",
            "trello", "Trello REST API, eigener Account",
            "taskwarrior", "Taskwarrior-CLI auf dem Host",
            "github", "GitHub REST API, eigener Account"
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
