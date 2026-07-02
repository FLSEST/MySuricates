package com.automation.service.integration;

import com.automation.service.integration.providers.github.GitHubProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

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
        return List.of(
                new GitHubProvider(new ObjectMapper())
                // + new JiraProvider(...), new TrelloProvider(...) once they implement TaskExportCapable
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
