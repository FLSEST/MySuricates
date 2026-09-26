package com.automation.service.integration;

import com.automation.service.integration.providers.google.GoogleGmailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleGmailProviderTest {

    private GoogleGmailProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GoogleGmailProvider(new ObjectMapper());
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("google-gmail");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("Gmail");
    }

    @Test
    void oauthConfigIsComplete() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.authorizationUrl()).contains("accounts.google.com");
        assertThat(config.tokenUrl()).contains("oauth2.googleapis.com");
        assertThat(config.defaultScopes()).containsExactly("https://www.googleapis.com/auth/gmail.readonly");
        assertThat(config.clientIdEnvVar()).isEqualTo("GOOGLE_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("GOOGLE_CLIENT_SECRET");
        assertThat(config.revocationUrl()).isEqualTo(OAuthConfig.GOOGLE_REVOCATION_URL);
    }

    @Test
    void has2Actions() {
        assertThat(provider.getAvailableActions()).hasSize(2);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_messages", "get_message");
    }

    @Test
    void actionsHaveInputSchemas() {
        for (IntegrationAction action : provider.getAvailableActions()) {
            assertThat(action.inputSchema()).isNotNull().isNotEmpty();
            assertThat(action.description()).isNotBlank();
            assertThat(action.requiredScopes()).contains("https://www.googleapis.com/auth/gmail.readonly");
        }
    }

    @Test
    void unknownActionThrows() {
        assertThatThrownBy(() -> provider.executeAction("unknown_action", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_action");
    }

    @Test
    void getMessageRequiresMessageId() {
        assertThatThrownBy(() -> provider.executeAction("get_message", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    /** ac639b39: maxResults 1.5 used to become 1 and 2^32 + 10 became 10 — read like its text, before any request. */
    @Test
    void listMessages_readsMaxResultsLikeItsText() {
        for (Object bad : new Object[] {1.5, 4_294_967_306L}) {
            assertThatThrownBy(() -> provider.executeAction("list_messages", Map.of("maxResults", bad), "token"))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'maxResults' must be a number");
        }
    }
}
