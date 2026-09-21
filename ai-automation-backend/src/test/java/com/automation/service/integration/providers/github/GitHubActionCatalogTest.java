package com.automation.service.integration.providers.github;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubActionCatalogTest {

    private final List<IntegrationAction> actions = GitHubActionCatalog.actions();

    private static IntegrationAction action(List<IntegrationAction> actions, String name) {
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void catalog_listsExactlyTheDispatchedActions() {
        // Must stay in sync with the switch in GitHubProvider.executeAction
        assertThat(actions).extracting(IntegrationAction::name)
                .containsExactly("list_repos", "list_issues", "get_issue", "create_issue", "update_issue", "add_comment");
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
    void writeActionsRequireRepoScope_readActionsNone() {
        for (String write : List.of("create_issue", "update_issue", "add_comment")) {
            assertThat(action(actions, write).requiredScopes()).as(write).containsExactly("repo");
        }
        for (String read : List.of("list_repos", "list_issues", "get_issue")) {
            assertThat(action(actions, read).requiredScopes()).as(read).isEmpty();
        }
    }

    @Test
    void issueActionsRequireRepoInOwnerSlashRepoForm() {
        assertThat(action(actions, "list_issues").inputSchema().get("required")).isEqualTo(List.of("repo"));
        assertThat(action(actions, "add_comment").inputSchema().get("required"))
                .isEqualTo(List.of("repo", "number", "body"));
        assertThat(action(actions, "list_repos").inputSchema()).doesNotContainKey("required");
    }
}
