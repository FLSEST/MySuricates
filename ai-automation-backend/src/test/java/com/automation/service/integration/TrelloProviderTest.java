package com.automation.service.integration;

import com.automation.service.integration.providers.trello.TrelloProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TrelloProviderTest {

    private TrelloProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TrelloProvider(new ObjectMapper());
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("trello");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("Trello");
    }

    @Test
    void oauthConfigIsComplete() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.authorizationUrl()).contains("auth.atlassian.com");
        assertThat(config.tokenUrl()).contains("auth.atlassian.com");
        assertThat(config.defaultScopes()).containsExactly("read:trello", "write:trello");
        assertThat(config.clientIdEnvVar()).isEqualTo("TRELLO_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("TRELLO_CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("audience", "api.atlassian.com");
    }

    @Test
    void has9Actions() {
        assertThat(provider.getAvailableActions()).hasSize(9);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_boards", "get_board_lists", "get_list_cards", "get_card",
                        "create_card", "update_card", "delete_card", "add_comment", "search");
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
        assertThatThrownBy(() -> provider.executeAction("unknown_action", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_action");
    }

    @Test
    void createCardRequiresIdList() {
        assertThatThrownBy(() -> provider.executeAction("create_card", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idList");
    }

    @Test
    void createCardRequiresName() {
        assertThatThrownBy(() -> provider.executeAction("create_card", java.util.Map.of("idList", "list1"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void updateCardRequiresCardId() {
        assertThatThrownBy(() -> provider.executeAction("update_card", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardId");
    }

    @Test
    void deleteCardRequiresCardId() {
        assertThatThrownBy(() -> provider.executeAction("delete_card", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardId");
    }

    @Test
    void addCommentRequiresCardId() {
        assertThatThrownBy(() -> provider.executeAction("add_comment", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardId");
    }

    @Test
    void addCommentRequiresText() {
        assertThatThrownBy(() -> provider.executeAction("add_comment", java.util.Map.of("cardId", "c1"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void searchRequiresQuery() {
        assertThatThrownBy(() -> provider.executeAction("search", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void getBoardListsRequiresBoardId() {
        assertThatThrownBy(() -> provider.executeAction("get_board_lists", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boardId");
    }

    @Test
    void getListCardsRequiresListId() {
        assertThatThrownBy(() -> provider.executeAction("get_list_cards", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("listId");
    }

    @Test
    void getCardRequiresCardId() {
        assertThatThrownBy(() -> provider.executeAction("get_card", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardId");
    }
}
