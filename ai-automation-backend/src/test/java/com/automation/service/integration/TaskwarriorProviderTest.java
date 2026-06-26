package com.automation.service.integration;

import com.automation.service.cli.TaskCommandService;
import com.automation.service.cli.TaskCommandService.TaskResult;
import com.automation.service.integration.providers.taskwarrior.TaskwarriorProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

class TaskwarriorProviderTest {

    private TaskCommandService taskCommandService;
    private TaskwarriorProvider provider;

    @BeforeEach
    void setUp() {
        taskCommandService = mock(TaskCommandService.class);
        provider = new TaskwarriorProvider(taskCommandService, new ObjectMapper());
    }

    // --- Metadata ---

    @Test
    void providerMetadata() {
        assertThat(provider.getProviderId()).isEqualTo("taskwarrior");
        assertThat(provider.getDisplayName()).isEqualTo("Taskwarrior");
        assertThat(provider.getIcon()).isEqualTo("ListTodo");
        assertThat(provider.getDescription()).isNotBlank();
    }

    @Test
    void doesNotRequireOAuthFlow() {
        assertThat(provider.requiresOAuthFlow()).isFalse();
    }

    @Test
    void oauthConfigMapsToTaskwarriorEnvVars() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config.clientIdEnvVar()).isEqualTo("TASKDATA");
        assertThat(config.clientSecretEnvVar()).isEqualTo("TASKRC");
    }

    @Test
    void exposesAllActions() {
        assertThat(provider.getAvailableActions().stream().map(IntegrationAction::name).toList())
                .containsExactly("list_tasks", "get_task", "add_task", "complete_task", "modify_task",
                        "annotate_task", "delete_task", "list_projects", "sync_task");
    }

    @Test
    void actionsHaveInputSchemasAndDescriptions() {
        for (IntegrationAction action : provider.getAvailableActions()) {
            assertThat(action.inputSchema()).isNotNull().isNotEmpty();
            assertThat(action.description()).isNotBlank();
        }
    }

    @Test
    void unknownActionThrows() {
        assertThatThrownBy(() -> provider.executeAction("unknown_action", Map.of(), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_action");
    }

    // --- list_tasks ---

    @Test
    void listTasksUsesDefaultPendingFilter() {
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of(Map.of("id", 1)));

        Object result = provider.executeAction("list_tasks", Map.of(), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).exportJson(captor.capture(), anyMap());
        assertThat(captor.getValue()).containsExactly("status:pending");
        assertThat(result).isInstanceOf(List.class);
    }

    @Test
    void listTasksSplitsCustomFilterIntoTokens() {
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of());

        provider.executeAction("list_tasks", Map.of("filter", "project:Haus +OVERDUE"), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).exportJson(captor.capture(), anyMap());
        assertThat(captor.getValue()).containsExactly("project:Haus", "+OVERDUE");
    }

    @Test
    void listTasksRejectsRcOverrideInFilter() {
        assertThatThrownBy(() -> provider.executeAction("list_tasks",
                Map.of("filter", "rc.data.location=/tmp/evil"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rc.");
        verifyNoInteractions(taskCommandService);
    }

    @Test
    void listTasksRejectsExecuteCommandInFilter() {
        assertThatThrownBy(() -> provider.executeAction("list_tasks",
                Map.of("filter", "execute rm -rf ~"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command keyword");
        verifyNoInteractions(taskCommandService);
    }

    // --- get_task ---

    @Test
    void getTaskRequiresId() {
        assertThatThrownBy(() -> provider.executeAction("get_task", Map.of(), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
    }

    @Test
    void getTaskRejectsInvalidId() {
        assertThatThrownBy(() -> provider.executeAction("get_task", Map.of("id", "1; rm -rf"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid task id");
    }

    @Test
    void getTaskReturnsSingleTask() {
        when(taskCommandService.exportJson(anyList(), anyMap()))
                .thenReturn(List.of(Map.of("id", 5, "description", "Milch kaufen")));

        Object result = provider.executeAction("get_task", Map.of("id", "5"), "|");

        assertThat(result).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result).get("description")).isEqualTo("Milch kaufen");
    }

    @Test
    void getTaskThrowsWhenNotFound() {
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of());
        assertThatThrownBy(() -> provider.executeAction("get_task", Map.of("id", "999"), "|"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No task found");
    }

    // --- add_task ---

    @Test
    void addTaskRequiresDescription() {
        assertThatThrownBy(() -> provider.executeAction("add_task", Map.of(), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void addTaskBuildsAttributeArgsAndLiteralDescription() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of(Map.of("id", 1)));

        provider.executeAction("add_task", Map.of(
                "description", "Buy: milk",
                "project", "Home",
                "priority", "h",
                "due", "tomorrow",
                "tags", List.of("shopping", "urgent")
        ), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).run(captor.capture(), anyMap(), any());
        List<String> args = captor.getValue();
        assertThat(args).startsWith("add");
        assertThat(args).contains("project:Home", "priority:H", "due:tomorrow", "+shopping", "+urgent");
        // Description must be the final arg, placed after the literal "--" separator.
        assertThat(args).endsWith("--", "Buy: milk");
    }

    @Test
    void addTaskRejectsInvalidPriority() {
        assertThatThrownBy(() -> provider.executeAction("add_task",
                Map.of("description", "x", "priority", "URGENT"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority");
    }

    @Test
    void addTaskReturnsLatestTask() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));
        when(taskCommandService.exportJson(eq(List.of("+LATEST")), anyMap()))
                .thenReturn(List.of(Map.of("id", 7, "description", "x")));

        Object result = provider.executeAction("add_task", Map.of("description", "x"), "|");
        assertThat(((Map<?, ?>) result).get("id")).isEqualTo(7);
    }

    // --- complete / delete ---

    @Test
    void completeTaskRunsDoneAndReturnsStatus() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));

        Object result = provider.executeAction("complete_task", Map.of("id", "3"), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).run(captor.capture(), anyMap(), any());
        assertThat(captor.getValue()).containsExactly("3", "done");
        assertThat(((Map<?, ?>) result).get("completed")).isEqualTo(true);
    }

    @Test
    void deleteTaskRunsDeleteAndReturnsStatus() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));

        Object result = provider.executeAction("delete_task", Map.of("id", "4"), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).run(captor.capture(), anyMap(), any());
        assertThat(captor.getValue()).containsExactly("4", "delete");
        assertThat(((Map<?, ?>) result).get("deleted")).isEqualTo(true);
    }

    @Test
    void writeActionPropagatesNonZeroExitAsError() {
        when(taskCommandService.run(anyList(), anyMap(), any()))
                .thenReturn(new TaskResult("No matches.", 1));

        assertThatThrownBy(() -> provider.executeAction("complete_task", Map.of("id", "999"), "|"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete task failed");
    }

    // --- modify / annotate ---

    @Test
    void modifyTaskRequiresAtLeastOneField() {
        assertThatThrownBy(() -> provider.executeAction("modify_task", Map.of("id", "2"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one field");
    }

    @Test
    void modifyTaskBuildsModifyArgs() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of(Map.of("id", 2)));

        provider.executeAction("modify_task", Map.of("id", "2", "project", "Work"), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).run(captor.capture(), anyMap(), any());
        assertThat(captor.getValue()).containsExactly("2", "modify", "project:Work");
    }

    @Test
    void annotateTaskRequiresText() {
        assertThatThrownBy(() -> provider.executeAction("annotate_task", Map.of("id", "2"), "|"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void annotateTaskUsesLiteralSeparator() {
        when(taskCommandService.run(anyList(), anyMap(), any())).thenReturn(new TaskResult("", 0));
        when(taskCommandService.exportJson(anyList(), anyMap())).thenReturn(List.of(Map.of("id", 2)));

        provider.executeAction("annotate_task", Map.of("id", "2", "text", "rc.gc=off note"), "|");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskCommandService).run(captor.capture(), anyMap(), any());
        // The note (even one resembling an rc override) stays literal behind "--".
        assertThat(captor.getValue()).containsExactly("2", "annotate", "--", "rc.gc=off note");
    }

    // --- list_projects ---

    @Test
    void listProjectsSplitsLines() {
        when(taskCommandService.run(eq(List.of("_projects")), anyMap(), any()))
                .thenReturn(new TaskResult("Home\nWork\n\nGarden", 0));

        Object result = provider.executeAction("list_projects", Map.of(), "|");
        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                .containsExactly("Home", "Work", "Garden");
    }

    // --- fetchAccountInfo ---

    @Test
    void fetchAccountInfoReturnsVersionJson() throws Exception {
        when(taskCommandService.run(eq(List.of("--version")), anyMap(), any()))
                .thenReturn(new TaskResult("3.1.0", 0));
        when(taskCommandService.run(eq(List.of("status:pending", "count")), anyMap(), any()))
                .thenReturn(new TaskResult("12", 0));

        String json = provider.fetchAccountInfo("|");
        assertThat(json).isNotNull();
        Map<?, ?> info = new ObjectMapper().readValue(json, Map.class);
        assertThat(info.get("version")).isEqualTo("3.1.0");
        assertThat(info.get("pendingCount")).isEqualTo(12);
    }

    @Test
    void fetchAccountInfoReturnsNullWhenTaskMissing() {
        when(taskCommandService.run(anyList(), anyMap(), any()))
                .thenThrow(new IllegalStateException("Cannot run program \"task\""));

        assertThat(provider.fetchAccountInfo("|")).isNull();
    }

    @Test
    void fetchAccountInfoPassesTaskdataEnv() {
        when(taskCommandService.run(eq(List.of("--version")), anyMap(), any()))
                .thenReturn(new TaskResult("3.1.0", 0));
        when(taskCommandService.run(eq(List.of("status:pending", "count")), anyMap(), any()))
                .thenReturn(new TaskResult("0", 0));

        provider.fetchAccountInfo("/data/tasks|/data/.taskrc");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskCommandService, atLeastOnce()).run(eq(List.of("--version")), envCaptor.capture(), any());
        assertThat(envCaptor.getValue())
                .containsEntry("TASKDATA", "/data/tasks")
                .containsEntry("TASKRC", "/data/.taskrc");
    }
}
