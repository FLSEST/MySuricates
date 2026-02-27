package com.automation.controller;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.TodoMapper;
import com.automation.service.system.ApiKeyService;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TodoController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private TodoService todoService;
    @MockBean private TodoMapper todoMapper;
    @MockBean private ApiKeyService apiKeyService;

    private TodoResponse sampleResponse() {
        return new TodoResponse("t-1", "Fix bug", "desc", Todo.Status.OPEN, Todo.Priority.HIGH,
                List.of("backend"), null, null, Todo.Source.AI, "claude", null, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    void listTodos_returnsPaged() throws Exception {
        when(todoService.findAll(0, 50, null, null, null, null, null, null))
                .thenReturn(new PageResponse<>(List.of(sampleResponse()), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("t-1"));
    }

    @Test
    void createTodo_returns201() throws Exception {
        var todo = Todo.builder().id("t-1").title("Fix bug").build();
        when(todoService.create(any())).thenReturn(todo);
        when(todoMapper.toResponse(todo)).thenReturn(sampleResponse());

        var request = new CreateTodoRequest("Fix bug", "desc", null, Todo.Priority.HIGH,
                List.of("backend"), null, Todo.Source.AI, "claude", null, null, null);

        mockMvc.perform(post("/api/v1/todos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Fix bug"));
    }

    @Test
    void getTodo_returnsById() throws Exception {
        when(todoService.findByIdResponse("t-1")).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/todos/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-1"));
    }

    @Test
    void updateTodoStatus_patchesStatus() throws Exception {
        var todo = Todo.builder().id("t-1").title("Fix bug").build();
        when(todoService.updateStatus("t-1", Todo.Status.DONE)).thenReturn(todo);
        when(todoService.findByIdResponse("t-1")).thenReturn(sampleResponse());

        mockMvc.perform(patch("/api/v1/todos/t-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateTodoStatusRequest(Todo.Status.DONE))))
                .andExpect(status().isOk());
    }

    @Test
    void deleteTodo_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/todos/t-1"))
                .andExpect(status().isNoContent());
        verify(todoService).delete("t-1");
    }

    @Test
    void countTodos_returnsCounts() throws Exception {
        when(todoService.count()).thenReturn(new TodoCountResponse(5, 2, 10));

        mockMvc.perform(get("/api/v1/todos/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(5))
                .andExpect(jsonPath("$.done").value(10));
    }
}
