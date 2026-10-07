package com.automation.service.todo;

import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoContextVariablesTest {

    @Test
    void of_fullTodo_mapsEveryField() {
        Todo todo = Todo.builder()
                .id("t-1").title("Fix it").description("Details")
                .tags("[\"bug\"]").priority(Todo.Priority.HIGH).projectId("p-1")
                .source(Todo.Source.WORKFLOW).referenceType("workflow_run").referenceId("wf-9")
                .build();

        assertThat(TodoContextVariables.of(todo)).isEqualTo(Map.of(
                "todo_id", "t-1",
                "todo_title", "Fix it",
                "todo_description", "Details",
                "todo_tags", "[\"bug\"]",
                "todo_priority", "HIGH",
                "todo_project_id", "p-1",
                "todo_source", "WORKFLOW",
                "todo_reference_type", "workflow_run",
                "todo_reference_id", "wf-9"));
    }

    @Test
    void of_missingOptionalFields_becomeEmptyStringsAndEmptyTagList() {
        // The builder defaults source to USER, so clear it to cover the null branch.
        Todo todo = Todo.builder().id("t-2").title("Bare").priority(Todo.Priority.LOW).source(null).build();

        Map<String, Object> context = TodoContextVariables.of(todo);

        assertThat(context)
                .containsEntry("todo_description", "")
                .containsEntry("todo_tags", "[]")
                .containsEntry("todo_project_id", "")
                .containsEntry("todo_source", "")
                .containsEntry("todo_reference_type", "")
                .containsEntry("todo_reference_id", "")
                .doesNotContainValue(null);
    }

    @Test
    void of_returnsAMutableMap_forCallerSpecificKeys() {
        Map<String, Object> context = TodoContextVariables.of(
                Todo.builder().id("t-3").title("T").priority(Todo.Priority.MEDIUM).build());

        context.put("todo_trigger_id", "trg-1");

        assertThat(context).containsEntry("todo_trigger_id", "trg-1");
    }
}
