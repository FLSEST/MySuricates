package com.automation.service.integration;

import com.automation.service.integration.providers.google.GoogleGmailProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThatThrownBy(() -> provider.executeAction("unknown_action", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_action");
    }

    @Test
    void getMessageRequiresMessageId() {
        assertThatThrownBy(() -> provider.executeAction("get_message", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }
}
