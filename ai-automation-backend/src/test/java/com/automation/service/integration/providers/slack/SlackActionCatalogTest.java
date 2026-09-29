package com.automation.service.integration.providers.slack;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackActionCatalogTest {

    private final List<IntegrationAction> actions = SlackActionCatalog.actions();

    @Test
    void namesAreUnique_andCoverTheThreeSlackActions() {
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("post_message", "list_channels", "get_channel_history");
    }

    @Test
    void everyActionHasDescriptionObjectSchemaAndScope() {
        for (IntegrationAction a : actions) {
            assertThat(a.description()).as(a.name()).isNotBlank();
            assertThat(a.inputSchema()).as(a.name()).containsEntry("type", "object");
            assertThat(a.inputSchema().get("properties")).as(a.name()).isInstanceOf(Map.class);
            assertThat(a.requiredScopes()).as(a.name()).isNotEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiredParametersAreDeclaredProperties() {
        for (IntegrationAction a : actions) {
            Map<Object, Object> props = (Map<Object, Object>) a.inputSchema().get("properties");
            Object required = a.inputSchema().get("required");
            if (required != null) {
                assertThat(props.keySet()).as(a.name()).containsAll((List<Object>) required);
            }
        }
    }

    @Test
    void scopesMatchTheirWebApiMethods() {
        assertThat(byName("post_message").requiredScopes()).containsExactly("chat:write");
        assertThat(byName("list_channels").requiredScopes()).containsExactly("channels:read");
        assertThat(byName("get_channel_history").requiredScopes()).containsExactly("channels:history");
    }

    private IntegrationAction byName(String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }
}
