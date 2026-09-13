package com.automation.service.integration.providers.jira;

import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JiraProviderTaskExportTest {

    private static final String BASE = "https://api.atlassian.com/ex/jira/cloud-1/rest/api/3";

    @Mock private JiraHttpClient http;
    private JiraProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JiraProvider(new ObjectMapper(), http);
    }

    @SuppressWarnings("unchecked")
    private void stubAccessibleSite() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("[{\"id\":\"cloud-1\",\"url\":\"https://acme.atlassian.net\"}]");
        when(http.getRaw(contains("/oauth/token/accessible-resources"), eq("tok"), any())).thenReturn(response);
    }

    @Test
    void createTask_postsTaskIssueIntoProject_andReturnsBrowseUrl() {
        stubAccessibleSite();
        when(http.post(eq(BASE + "/issue"), any(), eq("tok"), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("10001", "PROJ-7", BASE + "/issue/10001", Map.of()));

        TaskExportCapable.TaskRef ref = provider.createTask(
                new TaskExportCapable.TaskExportInput("Fix login", "Steps to reproduce", List.of("bug", "front end"), "PROJ"), "tok");

        assertThat(ref.referenceType()).isEqualTo("jira");
        assertThat(ref.referenceId()).isEqualTo("PROJ-7");
        assertThat(ref.url()).isEqualTo("https://acme.atlassian.net/browse/PROJ-7");

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).post(eq(BASE + "/issue"), body.capture(), eq("tok"), eq(JiraIssue.class));
        Map<?, ?> fields = (Map<?, ?>) ((Map<?, ?>) body.getValue()).get("fields");
        assertThat(fields.get("project")).isEqualTo(Map.of("key", "PROJ"));
        assertThat(fields.get("summary")).isEqualTo("Fix login");
        assertThat(fields.get("issuetype")).isEqualTo(Map.of("name", "Task"));
        assertThat(fields.get("description")).isNotNull();
        assertThat(fields.get("labels")).as("Jira labels must not contain whitespace").isEqualTo(List.of("bug", "front-end"));
    }

    @Test
    void createTask_blankTarget_failsBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.createTask(
                new TaskExportCapable.TaskExportInput("t", "b", List.of(), " "), "tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project key");
        verifyNoInteractions(http);
    }

    @Test
    void listTargets_mapsProjectSearchToKeyAndLabel() {
        stubAccessibleSite();
        when(http.get(eq(BASE + "/project/search?maxResults=100"), eq("tok"), eq(Object.class)))
                .thenReturn(Map.of("values", List.of(
                        Map.of("key", "PROJ", "name", "Project One"),
                        Map.of("key", "OPS"))));

        List<TaskExportCapable.TaskTarget> targets = provider.listTargets("tok");

        assertThat(targets).containsExactly(
                new TaskExportCapable.TaskTarget("PROJ", "PROJ – Project One"),
                new TaskExportCapable.TaskTarget("OPS", "OPS"));
    }

    @Test
    void createTask_withoutAccessibleSite_throwsWithoutPosting() {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(403);
        when(response.body()).thenReturn("nope");
        when(http.getRaw(anyString(), eq("tok"), any())).thenReturn(response);

        assertThatThrownBy(() -> provider.createTask(
                new TaskExportCapable.TaskExportInput("t", "b", List.of(), "PROJ"), "tok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloud ID");
        verify(http, never()).post(any(), any(), any(), any());
    }
}
