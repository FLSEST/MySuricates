package com.automation.service.integration;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.integration.providers.github.GitHubProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubProviderTest {

    private AppSettingsRepository appSettings;
    private GitHubProvider provider;

    @BeforeEach
    void setUp() {
        appSettings = mock(AppSettingsRepository.class);
        when(appSettings.get(anyString())).thenReturn(Optional.empty());
        provider = new GitHubProvider(new ObjectMapper(), appSettings);
    }

    private void allowlist(String value) {
        when(appSettings.get("github.repo-allowlist")).thenReturn(Optional.of(value));
    }

    @Test
    void providerId() {
        assertThat(provider.getProviderId()).isEqualTo("github");
    }

    @Test
    void displayName() {
        assertThat(provider.getDisplayName()).isEqualTo("GitHub");
    }

    @Test
    void usesPatNotOAuthFlow() {
        assertThat(provider.requiresOAuthFlow()).isFalse();
    }

    @Test
    void oauthConfigIsComplete() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.authorizationUrl()).contains("github.com");
        assertThat(config.tokenUrl()).contains("github.com");
        assertThat(config.defaultScopes()).isNotEmpty();
        assertThat(config.clientIdEnvVar()).isEqualTo("GITHUB_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("GITHUB_CLIENT_SECRET");
    }

    @Test
    void has6Actions() {
        assertThat(provider.getAvailableActions()).hasSize(6);
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_repos", "list_issues", "get_issue", "create_issue", "update_issue", "add_comment");
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
    void listIssuesRequiresRepo() {
        assertThatThrownBy(() -> provider.executeAction("list_issues", Map.of(), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repo");
    }

    @Test
    void getIssueRequiresNumber() {
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "owner/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
    }

    @Test
    void createIssueRequiresTitle() {
        assertThatThrownBy(() -> provider.executeAction("create_issue", Map.of("repo", "owner/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void updateIssueRequiresNumber() {
        assertThatThrownBy(() -> provider.executeAction("update_issue", Map.of("repo", "owner/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
    }

    @Test
    void addCommentRequiresBody() {
        assertThatThrownBy(() -> provider.executeAction("add_comment", Map.of("repo", "owner/repo", "number", 1), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void malformedRepoThrows() {
        assertThatThrownBy(() -> provider.executeAction("list_issues", Map.of("repo", "no-slash"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
    }

    @Test
    void implementsTaskExportCapable() {
        assertThat(provider).isInstanceOf(TaskExportCapable.class);
    }

    @Test
    void createTaskRejectsBlankTarget() {
        TaskExportCapable tc = provider;
        assertThatThrownBy(() -> tc.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", java.util.List.of(), ""), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
    }

    @Test
    void createTaskRejectsMalformedTarget() {
        TaskExportCapable tc = provider;
        assertThatThrownBy(() -> tc.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", java.util.List.of(), "no-slash"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/repo");
    }

    // --- github.repo-allowlist ---

    @Test
    void allowlistBlocksUnlistedRepo() {
        allowlist("FLSEST/MySuricates, other/repo");
        assertThatThrownBy(() -> provider.executeAction("list_issues", Map.of("repo", "evil/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.repo-allowlist")
                .hasMessageContaining("evil/repo");
    }

    @Test
    void allowlistPermitsListedRepoCaseInsensitive() {
        allowlist("FLSEST/MySuricates");
        // Passes the allowlist check and fails on the next validation (missing number) — no HTTP involved
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "flsest/mysuricates"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
    }

    @Test
    void allowlistSupportsOwnerWildcard() {
        allowlist("FLSEST/*");
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "FLSEST/brand-new-repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "other/brand-new-repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.repo-allowlist");
    }

    @Test
    void allowlistAppliesToCreateTask() {
        allowlist("FLSEST/MySuricates");
        TaskExportCapable tc = provider;
        assertThatThrownBy(() -> tc.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", java.util.List.of(), "evil/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.repo-allowlist");
    }

    @Test
    void emptyAllowlistValueAllowsAll() {
        allowlist("   ");
        // Legacy allow-all: validation passes, next check (missing number) fires
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "any/repo"), "token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("number");
    }
}
