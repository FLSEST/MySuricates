package com.automation.service.integration.providers.trello;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloActionCatalogTest {

    private final List<IntegrationAction> actions = TrelloActionCatalog.actions();

    private static IntegrationAction action(List<IntegrationAction> actions, String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void catalog_listsExactlyTheDispatchedActions() {
        // Must stay in sync with the switch in TrelloProvider.executeAction
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("list_boards", "get_board_lists", "get_list_cards", "get_card", "create_card", "update_card", "delete_card", "add_comment", "search");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySchema_isAnObject_whoseRequiredKeysAreDeclaredProperties() {
        for (IntegrationAction a : actions) {
            assertThat(a.description()).as(a.name()).isNotBlank();
            assertThat(a.inputSchema()).as(a.name()).containsEntry("type", "object");
            Map<String, Object> props = (Map<String, Object>) a.inputSchema().get("properties");
            assertThat(props).as(a.name() + " properties").isNotNull();
            props.forEach((key, spec) -> assertThat((Map<String, Object>) spec)
                    .as(a.name() + "." + key).containsKeys("type", "description"));
            Object required = a.inputSchema().get("required");
            if (required != null) {
                assertThat(props.keySet()).as(a.name() + " required ⊆ properties")
                        .containsAll((List<String>) required);
            }
        }
    }

    @Test
    void apiKeyProviderDeclaresNoScopes() {
        assertThat(actions).allSatisfy(a -> assertThat(a.requiredScopes()).as(a.name()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cardActionsKeyOnCardId_andCreateNeedsListAndName() {
        for (String name : List.of("get_card", "update_card", "delete_card", "add_comment")) {
            assertThat((List<String>) action(actions, name).inputSchema().get("required")).as(name).startsWith("cardId");
        }
        assertThat(action(actions, "create_card").inputSchema().get("required")).isEqualTo(List.of("idList", "name"));
        Map<String, Object> props = (Map<String, Object>) action(actions, "update_card").inputSchema().get("properties");
        assertThat((Map<String, Object>) props.get("closed")).containsEntry("type", "boolean");
    }
}
