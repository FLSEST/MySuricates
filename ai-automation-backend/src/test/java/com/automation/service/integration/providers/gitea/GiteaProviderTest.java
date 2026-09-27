package com.automation.service.integration.providers.gitea;

import com.automation.service.integration.IntegrationVerification;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GiteaProviderTest {

    private static final String CRED = "https://git.example.org/|tok";
    private static final String REPO_API = "https://git.example.org/api/v1/repos/org/app";

    @Mock private GiteaHttpClient http;
    private GiteaProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GiteaProvider(new ObjectMapper(), http);
    }

    @Test
    void metadata() {
        assertThat(provider.getProviderId()).isEqualTo("gitea");
        assertThat(provider.getDisplayName()).isEqualTo("Gitea / Forgejo");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getVerification()).isEqualTo(IntegrationVerification.VERIFIED);
        assertThat(provider.getVerifiedAgainst()).contains("Gitea");
    }

    @Test
    void fetchAccountInfo_readsUserFromTheConfiguredServer() {
        when(http.get("https://git.example.org/api/v1/user", "tok", Object.class))
                .thenReturn(Map.of("login", "alex", "full_name", "Alex S"));

        String info = provider.fetchAccountInfo(CRED);

        assertThat(info).contains("\"login\":\"alex\"", "\"name\":\"Alex S\"", "\"server\":\"https://git.example.org\"");
    }

    @Test
    void fetchAccountInfo_invalidUrlOrHttpFailure_returnsNull() {
        assertThat(provider.fetchAccountInfo("ftp://x|tok")).isNull();
        assertThat(provider.fetchAccountInfo("|tok")).isNull();
        verifyNoInteractions(http);

        when(http.get(anyString(), anyString(), eq(Object.class))).thenThrow(new IllegalStateException("Gitea API error 500"));
        assertThat(provider.fetchAccountInfo(CRED)).isNull();
    }

    @Test
    void fetchGrantedScopes_isUnknown() {
        assertThat(provider.fetchGrantedScopes(CRED)).isNull();
    }

    @Test
    void listRepos_usesLimitAndPage() {
        when(http.get(anyString(), eq("tok"), eq(Object.class))).thenReturn(List.of());

        provider.executeAction("list_repos", Map.of("perPage", 99, "page", 2), CRED);

        verify(http).get("https://git.example.org/api/v1/user/repos?limit=50&page=2", "tok", Object.class);
    }

    @Test
    void listIssues_filtersToIssuesAndPassesStateLabelsSince() {
        when(http.get(anyString(), eq("tok"), eq(Object.class))).thenReturn(List.of());

        provider.executeAction("list_issues", Map.of("repo", "org/app", "state", "all", "labels", "bug, ui",
                "since", "2026-01-01T00:00:00Z"), CRED);

        verify(http).get(REPO_API + "/issues?type=issues&state=all&limit=30&page=1&labels=bug%2Cui"
                + "&since=2026-01-01T00%3A00%3A00Z", "tok", Object.class);
    }

    @Test
    void getIssue_buildsIssuePath() {
        provider.executeAction("get_issue", Map.of("repo", "org/app", "number", 5), CRED);

        verify(http).get(REPO_API + "/issues/5", "tok", Object.class);
    }

    @Test
    void createIssue_resolvesLabelNamesToIds() {
        when(http.get(REPO_API + "/labels?limit=50&page=1", "tok", Object.class))
                .thenReturn(List.of(Map.of("id", 3, "name", "Bug"), Map.of("id", 4, "name", "ui")));
        when(http.post(eq(REPO_API + "/issues"), any(), eq("tok"), eq(Object.class))).thenReturn(Map.of("number", 9));

        provider.executeAction("create_issue", Map.of("repo", "org/app", "title", "T", "body", "B",
                "labels", "bug, 17", "assignees", "a,b"), CRED);

        verify(http).post(REPO_API + "/issues",
                Map.of("title", "T", "body", "B", "labels", List.of(17L, 3L), "assignees", List.of("a", "b")),
                "tok", Object.class);
    }

    @Test
    void createIssue_unknownLabel_failsBeforeWriting() {
        when(http.get(REPO_API + "/labels?limit=50&page=1", "tok", Object.class)).thenReturn(List.of());

        assertThatThrownBy(() -> provider.executeAction("create_issue",
                Map.of("repo", "org/app", "title", "T", "labels", "nope"), CRED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        verify(http, never()).post(anyString(), any(), anyString(), any());
    }

    @Test
    void updateIssue_patchesFieldsAndReplacesLabelsSeparately() {
        when(http.get(REPO_API + "/issues/5", "tok", Object.class)).thenReturn(Map.of("number", 5, "state", "closed"));

        Object result = provider.executeAction("update_issue", Map.of("repo", "org/app", "number", "5",
                "state", "Closed", "title", "Neu", "labels", "12"), CRED);

        verify(http).patch(REPO_API + "/issues/5", Map.of("state", "closed", "title", "Neu"), "tok", Object.class);
        verify(http).put(REPO_API + "/issues/5/labels", Map.of("labels", List.of(12L)), "tok", Object.class);
        assertThat(((Map<?, ?>) result).get("state")).isEqualTo("closed");
    }

    @Test
    void updateIssue_rejectsInvalidStateAndEmptyUpdate() {
        assertThatThrownBy(() -> provider.executeAction("update_issue",
                Map.of("repo", "org/app", "number", 5, "state", "done"), CRED))
                .hasMessageContaining("'open' or 'closed'");
        assertThatThrownBy(() -> provider.executeAction("update_issue", Map.of("repo", "org/app", "number", 5), CRED))
                .hasMessageStartingWith("Nothing to update");
        verifyNoInteractions(http);
    }

    @Test
    void addComment_postsBody() {
        provider.executeAction("add_comment", Map.of("repo", "org/app", "number", 5, "body", "Hi"), CRED);

        verify(http).post(REPO_API + "/issues/5/comments", Map.of("body", "Hi"), "tok", Object.class);
    }

    @Test
    void invalidInputs_failBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "org/app"), CRED))
                .hasMessage("Required parameter missing: number");
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "app", "number", 1), CRED))
                .hasMessageContaining("owner/repo");
        assertThatThrownBy(() -> provider.executeAction("list_repos", Map.of(), "tok-without-url"))
                .hasMessage("Server URL missing");
        assertThatThrownBy(() -> provider.executeAction("nope", Map.of(), CRED))
                .hasMessage("Unknown action: nope");
        verifyNoInteractions(http);
    }

    @Test
    void createTask_returnsGiteaRef_andDropsUnknownLabels() {
        when(http.get(REPO_API + "/labels?limit=50&page=1", "tok", Object.class))
                .thenReturn(List.of(Map.of("id", 3, "name", "bug")));
        when(http.post(eq(REPO_API + "/issues"), any(), eq("tok"), eq(Object.class)))
                .thenReturn(Map.of("number", 42, "html_url", "https://git.example.org/org/app/issues/42"));

        TaskExportCapable.TaskRef ref = provider.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", List.of("bug", "kein-label"), "org/app"), CRED);

        assertThat(ref).isEqualTo(new TaskExportCapable.TaskRef("gitea", "org/app#42", "https://git.example.org/org/app/issues/42"));
        Map<String, Object> expected = new HashMap<>(Map.of("title", "Titel", "body", "Body", "labels", List.of(3L)));
        verify(http).post(REPO_API + "/issues", expected, "tok", Object.class);
    }

    @Test
    void createTask_withoutNumberInResponse_fails() {
        when(http.post(anyString(), any(), anyString(), eq(Object.class))).thenReturn(Map.of());

        assertThatThrownBy(() -> provider.createTask(
                new TaskExportCapable.TaskExportInput("T", null, List.of(), "org/app"), CRED))
                .hasMessage("Gitea issue creation returned no number");
    }

    @Test
    void listTargets_mapsFullNames() {
        when(http.get("https://git.example.org/api/v1/user/repos?limit=50", "tok", Object.class))
                .thenReturn(List.of(Map.of("full_name", "org/app"), Map.of("name", "ohne")));

        assertThat(provider.listTargets(CRED)).containsExactly(new TaskExportCapable.TaskTarget("org/app", "org/app"));
    }
}
