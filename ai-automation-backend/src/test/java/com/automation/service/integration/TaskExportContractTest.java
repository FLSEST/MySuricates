package com.automation.service.integration;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.integration.providers.gitea.GiteaHttpClient;
import com.automation.service.integration.providers.gitea.GiteaProvider;
import com.automation.service.integration.providers.github.GitHubHttpClient;
import com.automation.service.integration.providers.github.GitHubProvider;
import com.automation.service.integration.providers.gitlab.GitLabHttpClient;
import com.automation.service.integration.providers.gitlab.GitLabProvider;
import com.automation.service.integration.providers.jira.JiraHttpClient;
import com.automation.service.integration.providers.jira.JiraProvider;
import com.automation.service.integration.providers.todoist.TodoistHttpClient;
import com.automation.service.integration.providers.todoist.TodoistProvider;
import com.automation.service.integration.providers.trello.TrelloHttpClient;
import com.automation.service.integration.providers.trello.TrelloProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for the integration-agnostic single-TODO export.
 * Every task sink must implement {@link TaskExportCapable} and reject an invalid target
 * fast (fail-fast validation, before any network call). This keeps the uniform contract
 * honest as new providers are added.
 *
 * When a new provider becomes a task sink, add it to {@link #taskSinks()}.
 */
class TaskExportContractTest {

    static List<IntegrationProvider> taskSinks() {
        AppSettingsRepository appSettings = mock(AppSettingsRepository.class);
        when(appSettings.get(anyString())).thenReturn(Optional.empty());
        return List.of(
                new GitHubProvider(new ObjectMapper(), appSettings, mock(GitHubHttpClient.class)),
                new JiraProvider(new ObjectMapper(), mock(JiraHttpClient.class)),
                new TrelloProvider(new ObjectMapper(), mock(TrelloHttpClient.class)),
                new GiteaProvider(new ObjectMapper(), mock(GiteaHttpClient.class)),
                new GitLabProvider(new ObjectMapper(), mock(GitLabHttpClient.class)),
                new TodoistProvider(new ObjectMapper(), mock(TodoistHttpClient.class))
        );
    }

    @Test
    void allTaskSinksImplementTheContract() {
        for (IntegrationProvider p : taskSinks()) {
            assertThat(p).as("%s must implement TaskExportCapable", p.getProviderId())
                    .isInstanceOf(TaskExportCapable.class);
        }
    }

    @Test
    void allTaskSinksRejectBlankTargetBeforeNetwork() {
        for (IntegrationProvider p : taskSinks()) {
            TaskExportCapable tc = (TaskExportCapable) p;
            assertThatThrownBy(() -> tc.createTask(
                    new TaskExportCapable.TaskExportInput("t", "b", List.of(), ""), "token"))
                    .as("%s must reject a blank target before any network call", p.getProviderId())
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
