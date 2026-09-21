package com.automation.service.integration.providers.taskwarrior;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskwarriorActionCatalogTest {

    private final List<IntegrationAction> actions = TaskwarriorActionCatalog.actions();

    private static IntegrationAction action(List<IntegrationAction> actions, String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void catalog_listsExactlyTheDispatchedActions() {
        // Must stay in sync with the switch in TaskwarriorProvider.executeAction
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("list_tasks", "get_task", "add_task", "complete_task", "modify_task", "annotate_task", "delete_task", "list_projects", "sync_task");
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
    void localCliActionsNeedNoOAuthScopes() {
        assertThat(actions).allSatisfy(a -> assertThat(a.requiredScopes()).as(a.name()).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void idBasedActionsRequireId_andTagsAreStringArrays() {
        for (String name : List.of("get_task", "complete_task", "modify_task", "annotate_task", "delete_task")) {
            assertThat((List<String>) action(actions, name).inputSchema().get("required")).as(name).startsWith("id");
        }
        Map<String, Object> props = (Map<String, Object>) action(actions, "add_task").inputSchema().get("properties");
        assertThat((Map<String, Object>) props.get("tags"))
                .containsEntry("type", "array")
                .containsEntry("items", Map.of("type", "string"));
        assertThat((Map<String, Object>) action(actions, "sync_task").inputSchema().get("properties")).isEmpty();
    }
}
