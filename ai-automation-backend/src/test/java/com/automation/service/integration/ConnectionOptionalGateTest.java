package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which shipped providers run without a stored connection (TODO cdccc1e5). The integrations page
 * shows them as ready instead of "Setup required"; an older backend lacks the field, so the frontend
 * falls back to the same list ({@code CONNECTION_OPTIONAL_FALLBACK} in
 * {@code components/integrations/providerReadiness.ts}) — a change here belongs there as well.
 */
class ConnectionOptionalGateTest {

    @Test
    void theConnectionOptionalProvidersAreExactlyTheKnownOnes() throws Exception {
        assertThat(IntegrationVerificationGateTest.shippedProviders().stream()
                .filter(IntegrationProvider::connectionOptional).map(IntegrationProvider::getProviderId))
                .containsExactlyInAnyOrder("open-meteo", "feed", "ical-feed", "taskwarrior", "ntfy");
    }

    /** Without a connection the token is null only for a provider that requires no credentials. */
    @Test
    void aConnectionOptionalProviderRequiresNoCredentials() throws Exception {
        for (IntegrationProvider p : IntegrationVerificationGateTest.shippedProviders()) {
            if (p.connectionOptional()) {
                assertThat(p.requiresCredentials())
                        .as("%s runs without a connection, so it must not require credentials", p.getProviderId())
                        .isFalse();
            }
        }
    }
}
