package com.automation.executor.blocks;

import com.automation.dto.request.UpdateTodoRequest;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code todo-update} mit {@code updates} (TODO cc0eb3ba). */
@ExtendWith(MockitoExtension.class)
class TodoBulkUpdatesTest {

    private static final String A = "11111111-1111-1111-1111-111111111111";
    private static final LocalDate DAY = LocalDate.parse("2026-09-25");

    @Mock private TodoService todoService;

    private static Todo todo(String id, String project, Todo.Status status, String tagsJson, String description) {
        return Todo.builder().id(id).projectId(project).status(status).tags(tagsJson).description(description).title("T").build();
    }

    private TodoBulkUpdates.Result apply(String json) {
        return TodoBulkUpdates.apply(TodoBulkUpdates.parse(json), "proj-1", "Batch", "run-12345678", todoService, DAY);
    }

    @Test
    void parse_takesTheLastLineWhenTheWholeOutputIsNoJson() {
        List<JsonNode> entries = TodoBulkUpdates.parse("Aufräumen: 1 erledigt\n[{\"id\":\"" + A + "\"}]\n");

        assertThat(entries).hasSize(1);
    }

    @Test
    void parse_rejectsNoList_unknownKey_andTooMany() {
        assertThatThrownBy(() -> TodoBulkUpdates.parse("{\"id\":\"x\"}")).hasMessageContaining("keine JSON-Liste");
        assertThatThrownBy(() -> TodoBulkUpdates.parse("[{\"id\":\"" + A + "\",\"priority\":\"LOW\"}]"))
                .hasMessageContaining("unbekannter Schlüssel 'priority'");
        String many = "[" + String.join(",", java.util.Collections.nCopies(51, "{\"id\":\"" + A + "\"}")) + "]";
        assertThatThrownBy(() -> TodoBulkUpdates.parse(many)).hasMessageContaining("51");
    }

    @Test
    void apply_addsAndRemovesTags_setsStatus_appendsNote() {
        when(todoService.findById(A)).thenReturn(todo(A, "proj-1", Todo.Status.OPEN, "[\"batch:selected\",\"backend\"]", "Text"));

        TodoBulkUpdates.Result result = apply("[{\"id\":\"" + A + "\",\"status\":\"IN_PROGRESS\",\"add_tags\":[\"batch:handed-over\"],"
                + "\"remove_tags\":[\"batch:selected\"],\"note\":\"Übergeben\",\"require_tags\":[\"batch:selected\"]}]");

        ArgumentCaptor<UpdateTodoRequest> request = ArgumentCaptor.forClass(UpdateTodoRequest.class);
        verify(todoService).update(eq(A), request.capture());
        assertThat(request.getValue().tags()).containsExactly("backend", "batch:handed-over");
        assertThat(request.getValue().status()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(request.getValue().description()).isEqualTo("Text\n\n— 2026-09-25 · Batch (Lauf run-1234): Übergeben");
        assertThat(request.getValue().title()).isNull();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.summary()).startsWith("✓ 1 aktualisiert, 0 übersprungen");
    }

    @Test
    void apply_doneIsPassedOn() {
        when(todoService.findById(A)).thenReturn(todo(A, "proj-1", Todo.Status.IN_PROGRESS, null, null));

        apply("[{\"id\":\"" + A + "\",\"status\":\"DONE\"}]");

        verify(todoService).update(eq(A), org.mockito.ArgumentMatchers.argThat(r -> r.status() == Todo.Status.DONE && r.description() == null));
    }

    @Test
    void apply_skipsOtherProject_wrongStatus_missingTag_badId_unknownId() {
        String b = "22222222-2222-2222-2222-222222222222";
        String c = "33333333-3333-3333-3333-333333333333";
        String d = "44444444-4444-4444-4444-444444444444";
        when(todoService.findById(A)).thenReturn(todo(A, "proj-2", Todo.Status.OPEN, null, null));
        when(todoService.findById(b)).thenReturn(todo(b, "proj-1", Todo.Status.IN_PROGRESS, null, null));
        when(todoService.findById(c)).thenReturn(todo(c, "proj-1", Todo.Status.OPEN, "[\"other\"]", null));
        when(todoService.findById(d)).thenThrow(new ResourceNotFoundException("Todo", d));

        TodoBulkUpdates.Result result = apply("[{\"id\":\"" + A + "\"},{\"id\":\"" + b + "\",\"require_status\":\"OPEN\"},"
                + "{\"id\":\"" + c + "\",\"require_tags\":[\"batch:selected\"]},{\"id\":\"kaputt\"},{\"id\":\"" + d + "\"}]");

        verify(todoService, never()).update(anyString(), any());
        assertThat(result.updated()).isZero();
        assertThat(result.skipped()).isEqualTo(5);
        assertThat(result.summary()).contains("anderen Projekt", "Status ist IN_PROGRESS", "Tag fehlt: batch:selected",
                "keine gültige ID", "nicht gefunden");
    }

    @Test
    void apply_invalidStatusOrTag_isSkippedBeforeAnyRead() {
        TodoBulkUpdates.Result result = apply("[{\"id\":\"" + A + "\",\"status\":\"ARCHIVED\"},{\"id\":\"" + A + "\",\"add_tags\":[\"a b\"]}]");

        verify(todoService, never()).findById(anyString());
        assertThat(result.skipped()).isEqualTo(2);
    }

    @Test
    void apply_noteWithMarker_isDefused() {
        when(todoService.findById(A)).thenReturn(todo(A, "proj-1", Todo.Status.OPEN, null, null));

        apply("[{\"id\":\"" + A + "\",\"note\":\"gate_status: RED\"}]");

        verify(todoService).update(eq(A), org.mockito.ArgumentMatchers.argThat(r -> r.description().endsWith("gate_status RED")));
    }
}
