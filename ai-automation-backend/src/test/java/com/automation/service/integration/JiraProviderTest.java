package com.automation.service.integration;

import com.automation.service.integration.providers.jira.JiraProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JiraProviderTest {

    private JiraProvider provider;
    private HttpClient mockHttpClient;

    @BeforeEach
    void setUp() throws Exception {
        provider = new JiraProvider(new ObjectMapper());
        mockHttpClient = mock(HttpClient.class);
        Field httpField = JiraProvider.class.getDeclaredField("httpClient");
        httpField.setAccessible(true);
        httpField.set(provider, mockHttpClient);
        Field cacheField = JiraProvider.class.getDeclaredField("cloudIdCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) cacheField.get(provider);
        cache.put("token", "cloud-id-123");
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

    // --- HTTP dispatch tests ---

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_listProjects_callsCorrectEndpoint() throws Exception {
        var resp = mockResponse(200,
                "{\"values\":[{\"id\":\"1\",\"key\":\"PROJ\",\"name\":\"Project\"}],\"total\":1}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("list_projects", Map.of(), "token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("/project/search");
        assertThat(result).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_searchIssues_returnsList() throws Exception {
        var resp = mockResponse(200,
                "{\"issues\":[],\"total\":0,\"maxResults\":20,\"startAt\":0}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("search_issues",
                Map.of("jql", "project=TEST"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("search/jql");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_getIssue_returnsIssue() throws Exception {
        var resp = mockResponse(200,
                "{\"id\":\"10001\",\"key\":\"TEST-1\",\"fields\":{\"summary\":\"S\"}}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("get_issue",
                Map.of("issueKey", "TEST-1"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("/issue/TEST-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_createIssue_postsAdfDescription() throws Exception {
        var resp = mockResponse(201, "{\"id\":\"10001\",\"key\":\"TEST-1\"}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        provider.executeAction("create_issue",
                Map.of("projectKey", "TEST", "summary", "My Issue", "description", "Desc text"),
                "token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(readBodyAsString(captor.getValue())).contains("\"type\":\"doc\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_updateIssue_callsPut() throws Exception {
        var resp = mockResponse(204, "");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("update_issue",
                Map.of("issueKey", "TEST-1", "summary", "Updated"), "token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().method()).isEqualTo("PUT");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap).containsEntry("updated", true).containsEntry("issueKey", "TEST-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_addComment_postsAdfBody() throws Exception {
        var resp = mockResponse(201,
                "{\"id\":\"comment-1\",\"body\":{\"type\":\"doc\",\"version\":1,\"content\":[]}}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        provider.executeAction("add_comment",
                Map.of("issueKey", "TEST-1", "body", "A comment"), "token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(readBodyAsString(captor.getValue())).contains("\"type\":\"doc\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_getTransitions_returnsList() throws Exception {
        var resp = mockResponse(200,
                "{\"transitions\":[{\"id\":\"31\",\"name\":\"Done\"}]}");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("get_transitions",
                Map.of("issueKey", "TEST-1"), "token");

        assertThat(result).isNotNull();
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(captor.getValue().uri().toString()).contains("/transitions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeAction_transitionIssue_postsTransitionId() throws Exception {
        var resp = mockResponse(204, "");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Object result = provider.executeAction("transition_issue",
                Map.of("issueKey", "TEST-1", "transitionId", "31"), "token");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockHttpClient).send(captor.capture(), any());
        assertThat(readBodyAsString(captor.getValue())).contains("\"id\":\"31\"");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertThat(resultMap)
                .containsEntry("transitioned", true)
                .containsEntry("transitionId", "31");
    }

    // --- checkResponse tests ---

    @Test
    void checkResponse_400_throwsIllegalState() {
        assertThatThrownBy(() -> invokeCheckResponse(mockResponse(400, "Bad request")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("400");
    }

    @Test
    void checkResponse_401_throws() {
        assertThatThrownBy(() -> invokeCheckResponse(mockResponse(401, "Unauthorized")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void checkResponse_403_throws() {
        assertThatThrownBy(() -> invokeCheckResponse(mockResponse(403, "Forbidden")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }

    @Test
    void checkResponse_500_throws() {
        assertThatThrownBy(() -> invokeCheckResponse(mockResponse(500, "Server Error")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test
    void checkResponse_200_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> invokeCheckResponse(mockResponse(200, "OK")));
    }

    // --- buildAdfText tests ---

    @Test
    @SuppressWarnings("unchecked")
    void buildAdfText_wrapsTextInAdfDocument() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("buildAdfText", String.class);
        m.setAccessible(true);
        Map<String, Object> adf = (Map<String, Object>) m.invoke(provider, "Hello World");

        assertThat(adf).containsEntry("type", "doc").containsEntry("version", 1);
        List<Map<String, Object>> content = (List<Map<String, Object>>) adf.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "paragraph");
        List<Map<String, Object>> paragraphContent =
                (List<Map<String, Object>>) content.get(0).get("content");
        assertThat(paragraphContent).hasSize(1);
        assertThat(paragraphContent.get(0))
                .containsEntry("type", "text")
                .containsEntry("text", "Hello World");
    }

    // --- paramInt tests ---

    @Test
    void paramInt_parsesNumber() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("paramInt", Map.class, String.class, int.class);
        m.setAccessible(true);
        assertThat((Integer) m.invoke(null, Map.of("n", 42), "n", 0)).isEqualTo(42);
    }

    @Test
    void paramInt_parsesStringNumber() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("paramInt", Map.class, String.class, int.class);
        m.setAccessible(true);
        assertThat((Integer) m.invoke(null, Map.of("n", "42"), "n", 0)).isEqualTo(42);
    }

    @Test
    void paramInt_returnsDefaultWhenMissing() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("paramInt", Map.class, String.class, int.class);
        m.setAccessible(true);
        assertThat((Integer) m.invoke(null, Map.of(), "n", 99)).isEqualTo(99);
    }

    // --- paramString tests ---

    @Test
    void paramString_returnsDefaultWhenMissing() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("paramString", Map.class, String.class, String.class);
        m.setAccessible(true);
        assertThat((String) m.invoke(null, Map.of(), "k", "default")).isEqualTo("default");
    }

    @Test
    void paramString_returnsValueAsString() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("paramString", Map.class, String.class, String.class);
        m.setAccessible(true);
        assertThat((String) m.invoke(null, Map.of("k", 42), "k", "default")).isEqualTo("42");
    }

    // --- resolveCloudId tests ---

    @Test
    void resolveCloudId_returnsCachedValueWithoutHttpCall() throws Exception {
        Method m = JiraProvider.class.getDeclaredMethod("resolveCloudId", String.class);
        m.setAccessible(true);

        String result = (String) m.invoke(provider, "token");

        assertThat(result).isEqualTo("cloud-id-123");
        verify(mockHttpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCloudId_cacheMiss_callsAccessibleResources() throws Exception {
        Field cacheField = JiraProvider.class.getDeclaredField("cloudIdCache");
        cacheField.setAccessible(true);
        Map<String, String> cache = (Map<String, String>) cacheField.get(provider);
        cache.remove("token");

        var resp = mockResponse(200,
                "[{\"id\":\"new-cloud\",\"name\":\"x\",\"url\":\"https://new-cloud.atlassian.net\"}]");
        doReturn(resp).when(mockHttpClient).send(any(HttpRequest.class), any());

        Method m = JiraProvider.class.getDeclaredMethod("resolveCloudId", String.class);
        m.setAccessible(true);
        String result = (String) m.invoke(provider, "token");

        assertThat(result).isEqualTo("new-cloud");
        assertThat(cache).containsKey("token");
        verify(mockHttpClient).send(any(), any());
    }

    // --- Test helpers ---

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mockResponse(int status, String body) {
        HttpResponse<String> resp = (HttpResponse<String>) mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    private void invokeCheckResponse(HttpResponse<String> resp) throws Throwable {
        Method m = JiraProvider.class.getDeclaredMethod("checkResponse", HttpResponse.class);
        m.setAccessible(true);
        try {
            m.invoke(provider, resp);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private String readBodyAsString(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        CompletableFuture<String> future = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            private final StringBuilder sb = new StringBuilder();

            @Override
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }

            @Override
            public void onNext(ByteBuffer b) { sb.append(StandardCharsets.UTF_8.decode(b.duplicate())); }

            @Override
            public void onError(Throwable t) { future.completeExceptionally(t); }

            @Override
            public void onComplete() { future.complete(sb.toString()); }
        });
        return future.get(5, TimeUnit.SECONDS);
    }
}
