package com.automation.service.integration.providers.github;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubProviderActionsTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private GitHubHttpClient http;
    private GitHubProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GitHubProvider(new ObjectMapper(), appSettingsRepository, http);
        lenient().when(appSettingsRepository.get("github.repo-allowlist")).thenReturn(Optional.of("FLSEST/*"));
    }

    @Test
    void createIssue_postsToRepoIssuesWithLabelsAsList() {
        when(http.post(eq("https://api.github.com/repos/FLSEST/MySuricates/issues"), any(), eq("ghp_x"), eq(Object.class)))
                .thenReturn(Map.of("number", 5));

        Object result = provider.executeAction("create_issue",
                Map.of("repo", "FLSEST/MySuricates", "title", "Bug", "labels", "a, b"), "flo|ghp_x");

        assertThat(((Map<?, ?>) result).get("number")).isEqualTo(5);
        verify(http).post(any(), eq(Map.of("title", "Bug", "labels", List.of("a", "b"))), eq("ghp_x"), eq(Object.class));
    }

    @Test
    void repoOutsideAllowlist_isRejectedBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.executeAction("get_issue",
                Map.of("repo", "someone/else", "number", 1), "ghp_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("github.repo-allowlist");
        verify(http, never()).get(any(), any(), any());
    }

    @Test
    void missingRequiredParam_isReportedBeforeAnyRequest() {
        assertThatThrownBy(() -> provider.executeAction("get_issue", Map.of("repo", "FLSEST/MySuricates"), "ghp_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Required parameter missing: number");
    }

    @Test
    void unknownAction_throws() {
        assertThatThrownBy(() -> provider.executeAction("nope", Map.of(), "ghp_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown action: nope");
    }

    @Test
    void createTask_returnsGithubRefWithNumberAndUrl() {
        when(http.post(any(), any(), eq("ghp_x"), eq(Object.class)))
                .thenReturn(Map.of("number", 42, "html_url", "https://github.com/FLSEST/MySuricates/issues/42"));

        TaskExportCapable.TaskRef ref = provider.createTask(
                new TaskExportCapable.TaskExportInput("Titel", "Body", List.of("bug"), "FLSEST/MySuricates"), "flo|ghp_x");

        assertThat(ref.referenceType()).isEqualTo("github");
        assertThat(ref.referenceId()).isEqualTo("FLSEST/MySuricates#42");
        assertThat(ref.url()).isEqualTo("https://github.com/FLSEST/MySuricates/issues/42");
    }

    @Test
    void fetchAccountInfo_onHttpFailure_returnsNull() {
        when(http.get(any(), any(), eq(Object.class))).thenThrow(new IllegalStateException("GitHub API error 500: x"));

        assertThat(provider.fetchAccountInfo("flo|ghp_x")).isNull();
    }
}
