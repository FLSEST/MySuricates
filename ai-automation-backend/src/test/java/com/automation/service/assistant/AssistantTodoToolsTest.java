package com.automation.service.assistant;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.entity.Todo;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantTodoToolsTest {

    @Mock private TodoService todoService;
    private AssistantTodoTools tools;

    @BeforeEach
    void setUp() {
        tools = new AssistantTodoTools(todoService, new ObjectMapper());
    }

    @Test
    void createTodo_exactSuccessLine_withTagsAndShortId() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("abcdefgh-1234").title("Fix").build());

        String r = tools.createTodo("{\"title\":\"Fix\",\"priority\":\"high\",\"tags\":[\"a\",\"b\"]}");

        assertThat(r).isEqualTo("✓ Todo created: \"Fix\" (priority: HIGH, Tags: a, b, ID: abcdefgh…)");
        ArgumentCaptor<CreateTodoRequest> req = ArgumentCaptor.forClass(CreateTodoRequest.class);
        verify(todoService).create(req.capture());
        assertThat(req.getValue().status()).isEqualTo(Todo.Status.OPEN);
        assertThat(req.getValue().priority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(req.getValue().tags()).containsExactly("a", "b");
        assertThat(req.getValue().source()).isEqualTo(Todo.Source.AI);
        assertThat(req.getValue().sourceAgent()).isEqualTo("assistant");
    }

    @Test
    void createTodo_noTags_shortIdOfShortIdIsWhole() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("abc").title("T").build());

        assertThat(tools.createTodo("{\"title\":\"T\"}"))
                .isEqualTo("✓ Todo created: \"T\" (priority: MEDIUM, ID: abc…)");
    }

    @Test
    void createTodo_invalidPriority_fallsBackToMedium() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("id").title("T").build());

        assertThat(tools.createTodo("{\"title\":\"T\",\"priority\":\"urgent\"}")).contains("(priority: MEDIUM");
    }

    @Test
    void createTodo_priorityIsTrimmed_andANullPriorityIsMedium() {
        when(todoService.create(any())).thenReturn(Todo.builder().id("id").title("T").build());

        assertThat(tools.createTodo("{\"title\":\"T\",\"priority\":\" critical \"}")).contains("(priority: CRITICAL");
        assertThat(tools.createTodo("{\"title\":\"T\",\"priority\":null}")).contains("(priority: MEDIUM");
    }

    @Test
    void createTodo_missingOrBlankTitle_errorLine() {
        assertThat(tools.createTodo("{}")).isEqualTo("⚠ Could not create todo: title is missing.");
        assertThat(tools.createTodo("{\"title\":\"  \"}")).isEqualTo("⚠ Could not create todo: title is missing.");
        verify(todoService, never()).create(any());
    }

    @Test
    void createTodo_serviceThrows_errorLineWithMessage() {
        when(todoService.create(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(tools.createTodo("{\"title\":\"T\"}")).isEqualTo("⚠ Could not create todo: db down");
    }

    @Test
    void createTodo_invalidJson_errorLine() {
        assertThat(tools.createTodo("{not json")).startsWith("⚠ Could not create todo: ");
    }

    @Test
    void updateTodoStatus_exactSuccessLine() {
        String r = tools.updateTodoStatus("{\"id\":\"123456789\",\"status\":\"done\"}");

        assertThat(r).isEqualTo("✓ Todo status updated: DONE (ID: 12345678…)");
        verify(todoService).updateStatus(eq("123456789"), eq(new UpdateTodoStatusRequest(Todo.Status.DONE, null)));
    }

    @Test
    void updateTodoStatus_missingFields_errorLine() {
        assertThat(tools.updateTodoStatus("{\"id\":\"x\"}"))
                .isEqualTo("⚠ update_todo_status: id and status are required fields.");
        assertThat(tools.updateTodoStatus("{\"status\":\"DONE\"}"))
                .isEqualTo("⚠ update_todo_status: id and status are required fields.");
    }

    /** a9bf13d5: the allowed values come from the enum — the hand-written list lacked ARCHIVED. */
    @Test
    void updateTodoStatus_invalidStatus_errorLine() {
        assertThat(tools.updateTodoStatus("{\"id\":\"x\",\"status\":\"nope\"}"))
                .isEqualTo("⚠ Invalid status: status muss OPEN, IN_PROGRESS, DONE, BACKLOG oder ARCHIVED sein, nicht 'nope'");
        verify(todoService, never()).updateStatus(any(), any());
    }

    @Test
    void updateTodoStatus_statusIsTrimmed() {
        assertThat(tools.updateTodoStatus("{\"id\":\"123456789\",\"status\":\" in_progress \"}"))
                .isEqualTo("✓ Todo status updated: IN_PROGRESS (ID: 12345678…)");
    }

    @Test
    void updateTodoStatus_serviceThrows_errorLine() {
        org.mockito.Mockito.doThrow(new RuntimeException("missing")).when(todoService).updateStatus(any(), any());

        assertThat(tools.updateTodoStatus("{\"id\":\"x\",\"status\":\"OPEN\"}"))
                .isEqualTo("⚠ Could not update status: missing");
        assertThat(List.of()).isEmpty();
    }
}
