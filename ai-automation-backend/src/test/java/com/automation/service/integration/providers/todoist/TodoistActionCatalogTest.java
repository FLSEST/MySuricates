package com.automation.service.integration.providers.todoist;

import com.automation.service.integration.IntegrationAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoistActionCatalogTest {

    private static IntegrationAction action(String name) {
        return TodoistActionCatalog.actions().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void everyActionHasAnObjectSchemaWithoutScopes() {
        for (IntegrationAction a : TodoistActionCatalog.actions()) {
            assertThat(a.inputSchema()).containsEntry("type", "object").containsKey("properties");
            assertThat(a.requiredScopes()).as("Todoist API tokens carry no scopes").isEmpty();
        }
    }

    @Test
    void requiredParamsMatchTheApi() {
        assertThat(action("create_task").inputSchema().get("required")).isEqualTo(List.of("content"));
        assertThat(action("update_task").inputSchema().get("required")).isEqualTo(List.of("task_id"));
        assertThat(action("close_task").inputSchema().get("required")).isEqualTo(List.of("task_id"));
        assertThat(action("add_comment").inputSchema().get("required")).isEqualTo(List.of("task_id", "content"));
        assertThat(action("list_tasks").inputSchema()).doesNotContainKey("required");
    }

    @Test
    void createTaskDocumentsTheSupportedFields() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) action("create_task").inputSchema().get("properties");
        assertThat(props.keySet()).containsExactlyInAnyOrder(
                "content", "description", "due_string", "priority", "labels", "project_id");
    }
}
