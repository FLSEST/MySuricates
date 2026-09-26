package com.automation.service.integration;

import com.automation.service.integration.providers.confluence.ConfluenceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConfluenceProviderTest {

    private ConfluenceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ConfluenceProvider(new ObjectMapper());
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("confluence");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("Confluence");
    }

    @Test
    void requiresOAuth() {
        assertThat(provider.requiresOAuthFlow()).isTrue();
    }

    @Test
    void oauthConfigIsComplete() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.authorizationUrl()).contains("auth.atlassian.com");
        assertThat(config.tokenUrl()).contains("auth.atlassian.com");
        assertThat(config.defaultScopes()).contains("read:confluence-content.all", "read:confluence-space.summary",
                "search:confluence", "offline_access");
        assertThat(config.clientIdEnvVar()).isEqualTo("CONFLUENCE_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("CONFLUENCE_CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("audience", "api.atlassian.com");
        // Atlassian 3LO publishes no revocation endpoint — revocation is skipped, not guessed.
        assertThat(config.revocationUrl()).isNull();
    }

    @Test
    void has4Actions() {
        assertThat(provider.getAvailableActions()).hasSize(4);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_spaces", "list_pages", "get_page", "search");
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
    void listPagesRequiresSpaceKey() {
        assertThatThrownBy(() -> provider.executeAction("list_pages", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spaceKey");
    }

    @Test
    void getPageRequiresPageId() {
        assertThatThrownBy(() -> provider.executeAction("get_page", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageId");
    }

    @Test
    void searchRequiresCql() {
        assertThatThrownBy(() -> provider.executeAction("search", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cql");
    }


    /**
     * ac639b39: limit 1.5 used to become 1 and 2^32 + 5 became 5 — read like its text now, and checked
     * before the cloud-id lookup, i.e. before any request to Atlassian.
     */
    @Test
    void limit_isReadLikeItsText_beforeAnyRequest() {
        for (Object bad : new Object[] {1.5, 4_294_967_301L}) {
            assertThatThrownBy(() -> provider.executeAction("search", Map.of("cql", "type = page", "limit", bad), "token"))
                    .as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Parameter 'limit' must be a number");
            assertThatThrownBy(() -> provider.executeAction("list_spaces", Map.of("limit", bad), "token"))
                    .hasMessage("Parameter 'limit' must be a number");
            assertThatThrownBy(() -> provider.executeAction("list_pages", Map.of("spaceKey", "DOCS", "limit", bad), "token"))
                    .hasMessage("Parameter 'limit' must be a number");
        }
    }
}
