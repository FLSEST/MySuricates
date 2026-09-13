package com.automation.service.integration;

import com.automation.service.integration.providers.jira.JiraComment;
import com.automation.service.integration.providers.jira.JiraHttpClient;
import com.automation.service.integration.providers.jira.JiraIssue;
import com.automation.service.integration.providers.jira.JiraProvider;
import com.automation.service.integration.providers.jira.JiraSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JiraProviderTest {

    private JiraProvider provider;
    private JiraHttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        http = mock(JiraHttpClient.class);
        provider = new JiraProvider(new ObjectMapper(), http);
        Field cacheField = JiraProvider.class.getDeclaredField("siteCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(provider);
        cache.put("token", newSite("cloud-id-123"));
    }

    /** JiraProvider.JiraSite is package-private in providers.jira — build it reflectively. */
    private static Object newSite(String cloudId) throws Exception {
        Class<?> site = Class.forName("com.automation.service.integration.providers.jira.JiraProvider$JiraSite");
        var ctor = site.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(cloudId, null);
    }

    // --- Existing tests (unchanged) ---

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
        assertThat(config.defaultScopes()).contains("read:jira-work", "read:jira-user");
        assertThat(config.defaultScopes()).doesNotContain("write:jira-work");
        assertThat(config.clientIdEnvVar()).isEqualTo("JIRA_CLIENT_ID");
        assertThat(config.clientSecretEnvVar()).isEqualTo("JIRA_CLIENT_SECRET");
        assertThat(config.extraAuthParams()).containsEntry("audience", "api.atlassian.com");
        // Atlassian 3LO publishes no revocation endpoint — revocation is skipped, not guessed.
        assertThat(config.revocationUrl()).isNull();
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

    // --- HTTP dispatch tests (migrated to a mocked JiraHttpClient) ---

    @Test
    void executeAction_listProjects_callsCorrectEndpoint() {
        when(http.get(anyString(), eq("token"), eq(Object.class)))
                .thenReturn(Map.of("values", List.of(Map.of("id", "1", "key", "PROJ", "name", "Project")), "total", 1));

        Object result = provider.executeAction("list_projects", Map.of(), "token");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).get(urlCaptor.capture(), eq("token"), eq(Object.class));
        assertThat(urlCaptor.getValue()).contains("/project/search");
        assertThat(result).isNotNull();
    }

    @Test
    void executeAction_searchIssues_returnsList() {
        when(http.get(anyString(), eq("token"), eq(JiraSearchResult.class)))
                .thenReturn(new JiraSearchResult(List.of(), 0, 20, 0));

        Object result = provider.executeAction("search_issues",
                Map.of("jql", "project=TEST"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).get(urlCaptor.capture(), eq("token"), eq(JiraSearchResult.class));
        assertThat(urlCaptor.getValue()).contains("search/jql");
    }

    @Test
    void executeAction_getIssue_returnsIssue() {
        when(http.get(anyString(), eq("token"), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("10001", "TEST-1", null, Map.of("summary", "S")));

        Object result = provider.executeAction("get_issue",
                Map.of("issueKey", "TEST-1"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).get(urlCaptor.capture(), eq("token"), eq(JiraIssue.class));
        assertThat(urlCaptor.getValue()).contains("/issue/TEST-1");
    }

    @Test
    void executeAction_createIssue_postsAdfDescription() throws Exception {
        when(http.post(anyString(), any(), eq("token"), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("10001", "TEST-1", null, null));

        provider.executeAction("create_issue",
                Map.of("projectKey", "TEST", "summary", "My Issue", "description", "Desc text"),
                "token");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(http).post(anyString(), bodyCaptor.capture(), eq("token"), eq(JiraIssue.class));
        assertThat(new ObjectMapper().writeValueAsString(bodyCaptor.getValue())).contains("\"type\":\"doc\"");
    }

    @Test
    void executeAction_updateIssue_callsPut() {
        Object result = provider.executeAction("update_issue",
                Map.of("issueKey", "TEST-1", "summary", "Updated"), "token");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).put(urlCaptor.capture(), any(), eq("token"));
        assertThat(urlCaptor.getValue()).contains("/issue/TEST-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("updated", true).containsEntry("issueKey", "TEST-1");
    }

    @Test
    void executeAction_addComment_postsAdfBody() throws Exception {
        when(http.post(anyString(), any(), eq("token"), eq(JiraComment.class)))
                .thenReturn(new JiraComment("comment-1", Map.of("type", "doc", "version", 1, "content", List.of()), null, null, null));

        provider.executeAction("add_comment",
                Map.of("issueKey", "TEST-1", "body", "A comment"), "token");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(http).post(anyString(), bodyCaptor.capture(), eq("token"), eq(JiraComment.class));
        assertThat(new ObjectMapper().writeValueAsString(bodyCaptor.getValue())).contains("\"type\":\"doc\"");
    }

    @Test
    void executeAction_getTransitions_returnsList() {
        when(http.get(anyString(), eq("token"), eq(Object.class)))
                .thenReturn(Map.of("transitions", List.of(Map.of("id", "31", "name", "Done"))));

        Object result = provider.executeAction("get_transitions",
                Map.of("issueKey", "TEST-1"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(http).get(urlCaptor.capture(), eq("token"), eq(Object.class));
        assertThat(urlCaptor.getValue()).contains("/transitions");
    }

    @Test
    void executeAction_transitionIssue_postsTransitionId() throws Exception {
        Object result = provider.executeAction("transition_issue",
                Map.of("issueKey", "TEST-1", "transitionId", "31"), "token");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(http).postNoResponse(anyString(), bodyCaptor.capture(), eq("token"));
        assertThat(new ObjectMapper().writeValueAsString(bodyCaptor.getValue())).contains("\"id\":\"31\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap)
                .containsEntry("transitioned", true)
                .containsEntry("transitionId", "31");
    }

    // --- resolveCloudId tests (mocked http.getRaw) ---

    @Test
    void resolveCloudId_returnsCachedValueWithoutHttpCall() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("resolveCloudId", String.class);
        m.setAccessible(true);

        String result = (String) m.invoke(provider, "token");

        assertThat(result).isEqualTo("cloud-id-123");
        verify(http, never()).getRaw(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCloudId_cacheMiss_callsAccessibleResources() throws Exception {
        Field cacheField = JiraProvider.class.getDeclaredField("siteCache");
        cacheField.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(provider);
        cache.remove("token");

        HttpResponse<String> resp = mockRawResponse(200,
                "[{\"id\":\"new-cloud\",\"name\":\"x\",\"url\":\"https://new-cloud.atlassian.net\"}]");
        when(http.getRaw(anyString(), eq("token"), any())).thenReturn(resp);

        Method m = JiraProvider.class.getDeclaredMethod("resolveCloudId", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(provider, "token");

        assertThat(result).isEqualTo("new-cloud");
        assertThat(cache).containsKey("token");
        verify(http).getRaw(anyString(), eq("token"), any());
    }

    // --- Test helpers ---

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockRawResponse(int status, String body) {
        HttpResponse<String> resp = (HttpResponse<String>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }
}
