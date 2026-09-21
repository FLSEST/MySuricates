package com.automation.service.integration.providers.jira;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraActionCatalogTest {

    private final List<IntegrationAction> actions = JiraActionCatalog.actions();

    private static IntegrationAction action(List<IntegrationAction> actions, String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void catalog_listsExactlyTheDispatchedActions() {
        // Must stay in sync with the switch in JiraProvider.executeAction
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("list_projects", "search_issues", "get_issue", "create_issue", "update_issue", "add_comment", "get_transitions", "transition_issue");
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
    void mutatingActionsRequireWriteScope_readActionsNone() {
        for (String write : List.of("create_issue", "update_issue", "add_comment", "transition_issue")) {
            assertThat(action(actions, write).requiredScopes()).as(write).containsExactly("write:jira-work");
        }
        for (String read : List.of("list_projects", "search_issues", "get_issue", "get_transitions")) {
            assertThat(action(actions, read).requiredScopes()).as(read).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createIssue_defaultsToTaskType() {
        Map<String, Object> props = (Map<String, Object>) action(actions, "create_issue").inputSchema().get("properties");
        assertThat((Map<String, Object>) props.get("issueType")).containsEntry("default", "Task");
        assertThat(action(actions, "create_issue").inputSchema().get("required")).isEqualTo(List.of("projectKey", "summary"));
    }
}
