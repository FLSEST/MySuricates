package com.automation.service.integration;

import com.automation.service.integration.providers.google.GoogleCalendarProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class GoogleCalendarProviderTest {

    private GoogleCalendarProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GoogleCalendarProvider(new ObjectMapper());
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("google-calendar");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("Google Calendar");
    }

    @Test
    void oauthConfigIsComplete() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.authorizationUrl()).contains("accounts.google.com");
        assertThat(config.tokenUrl()).contains("oauth2.googleapis.com");
        assertThat(config.defaultScopes()).isNotEmpty();
        assertThat(config.clientIdEnvVar()).isEqualTo("GOOGLE_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("GOOGLE_CLIENT_SECRET");
        assertThat(config.revocationUrl()).isEqualTo(OAuthConfig.GOOGLE_REVOCATION_URL);
    }

    @Test
    void has6Actions() {
        assertThat(provider.getAvailableActions()).hasSize(6);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_events", "get_event", "create_event", "update_event", "delete_event", "get_free_busy");
    }

    @Test
    void actionsHaveInputSchemas() {
        for (IntegrationAction action : provider.getAvailableActions()) {
            assertThat(action.inputSchema()).isNotNull().isNotEmpty();
            assertThat(action.description()).isNotBlank();
        }
    }

    @Test
    void unknownActionThrows() {
        assertThatThrownBy(() -> provider.executeAction("unknown_action", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_action");
    }

    @Test
    void createEventRequiresParams() {
        assertThatThrownBy(() -> provider.executeAction("create_event", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void getEventRequiresEventId() {
        assertThatThrownBy(() -> provider.executeAction("get_event", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void deleteEventRequiresEventId() {
        assertThatThrownBy(() -> provider.executeAction("delete_event", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void getFreeBusyRequiresTimeParams() {
        assertThatThrownBy(() -> provider.executeAction("get_free_busy", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeMin");
    }

    /** ac639b39: maxResults 1.5 used to become 1 and 2^32 + 10 became 10 — read like its text, before any request. */
    @Test
    void listEvents_readsMaxResultsLikeItsText() {
        for (Object bad : new Object[] {1.5, 4_294_967_306L}) {
            assertThatThrownBy(() -> provider.executeAction("list_events", Map.of("maxResults", bad), "token"))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'maxResults' must be a number");
        }
    }
}
