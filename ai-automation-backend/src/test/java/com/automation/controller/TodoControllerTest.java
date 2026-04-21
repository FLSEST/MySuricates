package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.Todo;
import com.automation.mapper.WorkflowMapper;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.TodoMapper;
import com.automation.service.system.ApiKeyService;
import com.automation.service.todo.TodoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.entity.Todo.Priority;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.todo.TodoSimilarityService;

@WebMvcTest(TodoController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private TodoService todoService;
    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean private TodoMapper todoMapper;
    @MockitoBean private WorkflowMapper workflowMapper;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private TodoSimilarityService todoSimilarityService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private TodoResponse sampleResponse() {
        return new TodoResponse("t-1", "Fix bug", "desc", Todo.Status.OPEN, Todo.Priority.HIGH,
                List.of("backend"), null, null, null, null, Todo.Source.AI, "claude", null, null, null, null,
                0, null, null, null, Instant.now(), Instant.now());
    }

    @Test
    void listTodos_returnsPaged() throws Exception {
        when(todoService.findAll(eq(0), eq(50), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<>(List.of(sampleResponse()), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("t-1"));
    }

    @Test
    void listTodos_multiStatus() throws Exception {
        when(todoService.findAll(eq(0), eq(50), isNull(), eq(List.of("OPEN", "IN_PROGRESS")), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<>(List.of(sampleResponse()), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/todos")
                        .param("status", "OPEN")
                        .param("status", "IN_PROGRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("t-1"));
    }

    @Test
    void listTodos_singleStatus() throws Exception {
        when(todoService.findAll(eq(0), eq(50), isNull(), eq(List.of("OPEN")), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new PageResponse<>(List.of(sampleResponse()), 0, 50, 1, 1));

        mockMvc.perform(get("/api/v1/todos").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("t-1"));
    }

    @Test
    void createTodo_returns201() throws Exception {
        var todo = Todo.builder().id("t-1").title("Fix bug").build();
        when(todoService.create(any())).thenReturn(todo);
        when(todoMapper.toResponse(todo)).thenReturn(sampleResponse());

        var request = new CreateTodoRequest("Fix bug", "desc", null, Todo.Priority.HIGH,
                List.of("backend"), null, null, Todo.Source.AI, "claude", null, null, null, null, null);

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
        var statusRequest = new UpdateTodoStatusRequest(Todo.Status.DONE, null);
        when(todoService.updateStatusAndReturn(eq("t-1"), any(UpdateTodoStatusRequest.class))).thenReturn(sampleResponse());

        mockMvc.perform(patch("/api/v1/todos/t-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void resolveTodo_returnsRun() throws Exception {
        var run = com.automation.entity.WorkflowRun.builder().id("run-1").build();
        var response = new WorkflowRunResponse("run-1", "wf-1", "Resolver", null, null,
                com.automation.entity.RunStatus.RUNNING, List.of(), Instant.now(), null, null, null, null, java.util.Map.of(), java.util.Map.of(), null);
        when(todoService.resolve("t-1")).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(response);

        mockMvc.perform(post("/api/v1/todos/t-1/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"));
    }

    @Test
    void deleteTodo_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/todos/t-1"))
                .andExpect(status().isNoContent());
        verify(todoService).delete("t-1");
    }

    @Test
    void countTodos_returnsCounts() throws Exception {
        when(todoService.count()).thenReturn(new TodoCountResponse(5, 2, 10, 3));

        mockMvc.perform(get("/api/v1/todos/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(5))
                .andExpect(jsonPath("$.done").value(10));
    }

    @Test
    void findSimilar_validParams_returnsOk() throws Exception {
        Todo similar = Todo.builder()
                .id("t-2").title("Similar bug").priority(Priority.HIGH).build();
        when(todoSimilarityService.findSimilar(eq("proj-1"), eq("Fix bug"), isNull()))
                .thenReturn(List.of(new TodoSimilarityService.SimilarTodo(
                        similar, 0.85, TodoSimilarityService.Similarity.SAME)));

        mockMvc.perform(get("/api/v1/todos/similar")
                        .param("projectId", "proj-1")
                        .param("title", "Fix bug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-2"))
                .andExpect(jsonPath("$[0].title").value("Similar bug"))
                .andExpect(jsonPath("$[0].similarity").value("SAME"));
    }

    @Test
    void findSimilar_noMatches_returnsEmptyList() throws Exception {
        when(todoSimilarityService.findSimilar(anyString(), anyString(), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/todos/similar")
                        .param("projectId", "proj-1")
                        .param("title", "Unique"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void findSimilar_missingProjectId_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/todos/similar")
                        .param("title", "Fix bug"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void findSimilar_missingTitle_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/todos/similar")
                        .param("projectId", "proj-1"))
                .andExpect(status().is5xxServerError());
    }
}
