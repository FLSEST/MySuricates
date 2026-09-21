package com.automation.service.integration.providers.jira;

import com.automation.service.integration.TaskExportCapable.TaskExportInput;
import com.automation.service.integration.TaskExportCapable.TaskRef;
import com.automation.service.integration.TaskExportCapable.TaskTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JiraTaskExporterTest {

    private static final String BASE = "https://api.atlassian.com/ex/jira/c1/rest/api/3";

    @Mock private JiraHttpClient http;

    @SuppressWarnings("unchecked")
    private Map<String, Object> postedFields() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(http).post(eq(BASE + "/issue"), body.capture(), eq("tok"), eq(JiraIssue.class));
        return (Map<String, Object>) ((Map<String, Object>) body.getValue()).get("fields");
    }

    @Test
    void requireProjectKey_trimsAndRejectsBlank() {
        assertThat(JiraTaskExporter.requireProjectKey("  PROJ ")).isEqualTo("PROJ");
        assertThatThrownBy(() -> JiraTaskExporter.requireProjectKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JiraTaskExporter.requireProjectKey("\t")).hasMessageContaining("project key");
    }

    @Test
    void createTask_blankTitleAndBody_andOnlyBlankLabels_sendMinimalFields() {
        when(http.post(eq(BASE + "/issue"), any(), eq("tok"), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("1", "PROJ-1", BASE + "/issue/1", Map.of()));

        JiraTaskExporter.createTask(http, BASE, "https://acme.atlassian.net", "PROJ",
                new TaskExportInput(" ", "", Arrays.asList(" ", null), "PROJ"), "tok");

        Map<String, Object> fields = postedFields();
        assertThat(fields).containsOnlyKeys("project", "summary", "issuetype");
        assertThat(fields.get("summary")).isEqualTo("TODO");
    }

    @Test
    void createTask_labelsAreTrimmedAndWhitespaceCollapsedToDash() {
        when(http.post(eq(BASE + "/issue"), any(), eq("tok"), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("1", "PROJ-1", null, Map.of()));

        JiraTaskExporter.createTask(http, BASE, "https://acme.atlassian.net", "PROJ",
                new TaskExportInput("T", null, List.of("  needs   triage ", "ok"), "PROJ"), "tok");

        assertThat(postedFields().get("labels")).isEqualTo(List.of("needs-triage", "ok"));
    }

    @Test
    void createTask_withoutSiteUrl_fallsBackToApiSelfLink() {
        when(http.post(any(), any(), any(), eq(JiraIssue.class)))
                .thenReturn(new JiraIssue("1", "PROJ-9", BASE + "/issue/1", Map.of()));

        TaskRef ref = JiraTaskExporter.createTask(http, BASE, null, "PROJ",
                new TaskExportInput("T", "B", null, "PROJ"), "tok");

        assertThat(ref).isEqualTo(new TaskRef("jira", "PROJ-9", BASE + "/issue/1"));
    }

    @Test
    void createTask_responseWithoutKey_fails() {
        when(http.post(any(), any(), any(), eq(JiraIssue.class)))
                .thenReturn(null)
                .thenReturn(new JiraIssue("1", null, null, Map.of()));
        TaskExportInput input = new TaskExportInput("T", null, null, "PROJ");

        assertThatThrownBy(() -> JiraTaskExporter.createTask(http, BASE, null, "PROJ", input, "tok"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no key");
        assertThatThrownBy(() -> JiraTaskExporter.createTask(http, BASE, null, "PROJ", input, "tok"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listTargets_skipsEntriesWithoutKey_andToleratesUnexpectedShapes() {
        when(http.get(BASE + "/project/search?maxResults=100", "tok", Object.class))
                .thenReturn(Map.of("values", List.of(Map.of("name", "no key"), "garbage", Map.of("key", "OPS"))))
                .thenReturn(List.of("not a page"))
                .thenReturn(null);

        assertThat(JiraTaskExporter.listTargets(http, BASE, "tok")).containsExactly(new TaskTarget("OPS", "OPS"));
        assertThat(JiraTaskExporter.listTargets(http, BASE, "tok")).isEmpty();
        assertThat(JiraTaskExporter.listTargets(http, BASE, "tok")).isEmpty();
    }
}
