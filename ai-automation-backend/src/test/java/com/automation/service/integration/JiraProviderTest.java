package com.automation.service.integration;

import com.automation.service.integration.providers.jira.JiraProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JiraProviderTest {

    private JiraProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JiraProvider(new ObjectMapper());
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("jira");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("Jira");
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
        assertThat(config.defaultScopes()).contains("read:jira-work", "write:jira-work");
        assertThat(config.clientIdEnvVar()).isEqualTo("JIRA_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("JIRA_CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("audience", "api.atlassian.com");
    }

    @Test
    void has8Actions() {
        assertThat(provider.getAvailableActions()).hasSize(8);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_projects", "search_issues", "get_issue", "create_issue",
                        "update_issue", "add_comment", "get_transitions", "transition_issue");
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
    void searchIssuesRequiresJql() {
        assertThatThrownBy(() -> provider.executeAction("search_issues", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jql");
    }

    @Test
    void getIssueRequiresIssueKey() {
        assertThatThrownBy(() -> provider.executeAction("get_issue", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }

    @Test
    void createIssueRequiresProjectKey() {
        assertThatThrownBy(() -> provider.executeAction("create_issue", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectKey");
    }

    @Test
    void createIssueRequiresSummary() {
        assertThatThrownBy(() -> provider.executeAction("create_issue", java.util.Map.of("projectKey", "PROJ"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void updateIssueRequiresIssueKey() {
        assertThatThrownBy(() -> provider.executeAction("update_issue", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }

    @Test
    void addCommentRequiresIssueKey() {
        assertThatThrownBy(() -> provider.executeAction("add_comment", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }

    @Test
    void addCommentRequiresBody() {
        assertThatThrownBy(() -> provider.executeAction("add_comment", java.util.Map.of("issueKey", "PROJ-1"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void transitionIssueRequiresIssueKey() {
        assertThatThrownBy(() -> provider.executeAction("transition_issue", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }

    @Test
    void transitionIssueRequiresTransitionId() {
        assertThatThrownBy(() -> provider.executeAction("transition_issue", java.util.Map.of("issueKey", "PROJ-1"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transitionId");
    }

    @Test
    void getTransitionsRequiresIssueKey() {
        assertThatThrownBy(() -> provider.executeAction("get_transitions", java.util.Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issueKey");
    }
}
