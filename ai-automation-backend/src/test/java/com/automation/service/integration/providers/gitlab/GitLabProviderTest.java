package com.automation.service.integration.providers.gitlab;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Request shapes follow the official GitLab REST API v4 docs (issues, notes, projects, users, personal access tokens). */
@ExtendWith(MockitoExtension.class)
class GitLabProviderTest {

    private static final String CRED = "https://gitlab.example.org/|glpat";
    private static final String API = "https://gitlab.example.org/api/v4";

    @Mock private GitLabHttpClient http;
    private GitLabProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitLabProvider(new ObjectMapper(), http);
    }

    @Test
    void metadata_isSimulated() {
        assertThat(provider.getProviderId()).isEqualTo("gitlab");
        assertThat(provider.getDisplayName()).isEqualTo("GitLab");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.getVerification()).isEqualTo(IntegrationVerification.SIMULATED);
        assertThat(provider.getVerifiedAgainst()).isNull();
    }

    @Test
    void emptyServerUrl_defaultsToGitlabCom() {
        when(http.get("https://gitlab.com/api/v4/user", "glpat", Object.class)).thenReturn(Map.of("username", "flo"));

        assertThat(provider.fetchAccountInfo("|glpat")).contains("\"server\":\"https://gitlab.com\"");
        assertThat(provider.fetchAccountInfo("glpat")).contains("\"login\":\"flo\"");
    }

    @Test
    void fetchAccountInfo_readsUsernameNameAndEmail() {
        when(http.get(API + "/user", "glpat", Object.class))
                .thenReturn(Map.of("username", "flo", "name", "Flo S", "email", "flo@example.org"));

        String info = provider.fetchAccountInfo(CRED);

        assertThat(info).contains("\"login\":\"flo\"", "\"name\":\"Flo S\"", "\"email\":\"flo@example.org\"",
                "\"server\":\"https://gitlab.example.org\"");
    }

    @Test
    void fetchAccountInfo_invalidUrlOrFailure_returnsNull() {
        assertThat(provider.fetchAccountInfo("gitlab.example.org|glpat")).isNull();
        verifyNoInteractions(http);
        when(http.get(anyString(), anyString(), eq(Object.class))).thenThrow(new IllegalStateException("GitLab API error 500"));
        assertThat(provider.fetchAccountInfo(CRED)).isNull();
    }

    @Test
    void fetchGrantedScopes_readsTokenSelf() {
        when(http.get(API + "/personal_access_tokens/self", "glpat", Object.class))
                .thenReturn(Map.of("name", "ms", "scopes", List.of("api", "read_user")));

        assertThat(provider.fetchGrantedScopes(CRED)).isEqualTo("api read_user");
    }

    @Test
    void fetchGrantedScopes_unknownWhenEndpointFailsOrIsEmpty() {
        when(http.get(API + "/personal_access_tokens/self", "glpat", Object.class))
                .thenThrow(new IllegalStateException("GitLab API error 404: {}"))
                .thenReturn(Map.of("scopes", List.of()));

        assertThat(provider.fetchGrantedScopes(CRED)).isNull();
        assertThat(provider.fetchGrantedScopes(CRED)).isNull();
    }

    @Test
    void listProjects_onlyMemberships() {
        provider.executeAction("list_projects", Map.of("search", "app x", "perPage", 10), CRED);

        verify(http).get(API + "/projects?membership=true&simple=true&order_by=last_activity_at&per_page=10&page=1"
                + "&search=app+x", "glpat", Object.class);
    }

    @Test
    void listIssues_mapsStateLabelsAndSince() {
        provider.executeAction("list_issues", Map.of("project", "grp/app", "labels", "bug,ui",
                "since", "2026-01-01T00:00:00Z"), CRED);
        provider.executeAction("list_issues", Map.of("project", "grp/app", "state", "all"), CRED);

        verify(http).get(API + "/projects/grp%2Fapp/issues?per_page=30&page=1&state=opened&labels=bug%2Cui"
                + "&updated_after=2026-01-01T00%3A00%3A00Z", "glpat", Object.class);
        verify(http).get(API + "/projects/grp%2Fapp/issues?per_page=30&page=1", "glpat", Object.class);
    }

    @Test
    void getIssue_usesIid() {
        provider.executeAction("get_issue", Map.of("project", "42", "number", 7), CRED);

        verify(http).get(API + "/projects/42/issues/7", "glpat", Object.class);
    }

    @Test
    void createIssue_mapsBodyToDescriptionAndLabelsToString() {
        provider.executeAction("create_issue", Map.of("project", "grp/sub/app", "title", "T", "body", "B",
                "labels", "bug, ui", "assigneeIds", "3,4"), CRED);

        verify(http).post(API + "/projects/grp%2Fsub%2Fapp/issues",
                Map.of("title", "T", "description", "B", "labels", "bug,ui", "assignee_ids", List.of(3L, 4L)),
                "glpat", Object.class);
    }

    @Test
    void updateIssue_closesViaStateEvent() {
        provider.executeAction("update_issue", Map.of("project", "grp/app", "number", "7", "state", "closed",
                "title", "Neu"), CRED);

        verify(http).put(API + "/projects/grp%2Fapp/issues/7",
                Map.of("state_event", "close", "title", "Neu"), "glpat", Object.class);
    }

    @Test
    void updateIssue_reopensAndEmptyLabelsClear() {
        provider.executeAction("update_issue", Map.of("project", "grp/app", "number", 7, "state", "open", "labels", ""), CRED);

        verify(http).put(API + "/projects/grp%2Fapp/issues/7",
                Map.of("state_event", "reopen", "labels", ""), "glpat", Object.class);
    }

    @Test
    void updateIssue_withoutChanges_failsBeforeRequest() {
        assertThatThrownBy(() -> provider.executeAction("update_issue", Map.of("project", "grp/app", "number", 7), CRED))
                .hasMessageStartingWith("Nothing to update");
        verifyNoInteractions(http);
    }

    @Test
    void addComment_postsNote() {
        provider.executeAction("add_comment", Map.of("project", "grp/app", "number", 7, "body", "Hi"), CRED);

        verify(http).post(API + "/projects/grp%2Fapp/issues/7/notes", Map.of("body", "Hi"), "glpat", Object.class);
    }

    @Test
    void invalidInputs_failBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("project", "grp/app"), CRED))
                .hasMessage("Required parameter missing: number");
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("project", "grp/../x", "number", 1), CRED))
                .hasMessageContaining("invalid path segment");
        assertThatThrownBy(() -> provider.executeAction("list_projects", Map.of(), "ftp://x|glpat"))
                .hasMessageContaining("http:// or https://");
        assertThatThrownBy(() -> provider.executeAction("nope", Map.of(), CRED))
                .hasMessage("Unknown action: nope");
        verifyNoInteractions(http);
    }

    @Test
    void createTask_returnsGitlabRefWithIidAndWebUrl() {
        when(http.post(eq(API + "/projects/grp%2Fapp/issues"), any(), eq("glpat"), eq(Object.class)))
                .thenReturn(Map.of("id", 999, "iid", 12, "web_url", "https://gitlab.example.org/grp/app/-/issues/12"));

        TaskExportCapable.TaskRef ref = provider.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", List.of("bug", "ui"), "grp/app"), CRED);

        assertThat(ref).isEqualTo(new TaskExportCapable.TaskRef("gitlab", "grp/app#12",
                "https://gitlab.example.org/grp/app/-/issues/12"));
        verify(http).post(API + "/projects/grp%2Fapp/issues",
                new HashMap<>(Map.of("title", "Titel", "description", "Body", "labels", "bug,ui")), "glpat", Object.class);
    }

    @Test
    void createTask_withoutIid_fails() {
        when(http.post(anyString(), any(), anyString(), eq(Object.class))).thenReturn(Map.of("id", 1));

        assertThatThrownBy(() -> provider.createTask(
                new TaskExportCapable.TaskExportInput("T", null, List.of(), "grp/app"), CRED))
                .hasMessage("GitLab issue creation returned no iid");
    }

    @Test
    void listTargets_usesPathWithNamespace() {
        when(http.get(API + "/projects?membership=true&simple=true&order_by=last_activity_at&per_page=100", "glpat", Object.class))
                .thenReturn(List.of(Map.of("path_with_namespace", "grp/app", "name_with_namespace", "Grp / App"),
                        Map.of("name", "ohne Pfad")));

        assertThat(provider.listTargets(CRED)).containsExactly(new TaskExportCapable.TaskTarget("grp/app", "Grp / App"));
    }
}
