package com.automation.controller;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.mapper.TodoMapper;
import com.automation.service.todo.TodoService;
import com.automation.service.todo.TodoSimilarityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/todos")
@RequiredArgsConstructor
public class TodoController {

    private final TodoService todoService;
    private final TodoMapper todoMapper;
    private final TodoSimilarityService similarityService;

    @GetMapping
    public PageResponse<TodoResponse> listTodos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String q) {
        return todoService.findAll(page, size, projectId, status, priority, source, tag, q);
    }

    @PostMapping
    public ResponseEntity<TodoResponse> createTodo(@Valid @RequestBody CreateTodoRequest request) {
        var todo = todoService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(todoMapper.toResponse(todo));
    }

    @GetMapping("/{id}")
    public TodoResponse getTodo(@PathVariable String id) {
        return todoService.findByIdResponse(id);
    }

    @PutMapping("/{id}")
    public TodoResponse updateTodo(@PathVariable String id, @RequestBody UpdateTodoRequest request) {
        var todo = todoService.update(id, request);
        return todoService.findByIdResponse(todo.getId());
    }

    @PatchMapping("/{id}/status")
    public TodoResponse updateTodoStatus(@PathVariable String id,
                                         @Valid @RequestBody UpdateTodoStatusRequest request) {
        var todo = todoService.updateStatus(id, request);
        return todoService.findByIdResponse(todo.getId());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodo(@PathVariable String id) {
        todoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public TodoCountResponse countTodos() {
        return todoService.count();
    }

    /**
     * Check for similar TODOs before creating a new one.
     * Frontend calls this to offer merge/link options to the user.
     */
    @GetMapping("/similar")
    public List<SimilarTodoResponse> findSimilar(
            @RequestParam String projectId,
            @RequestParam String title) {
        return similarityService.findSimilar(projectId, title, null).stream()
                .map(s -> new SimilarTodoResponse(
                        s.todo().getId(),
                        s.todo().getTitle(),
                        s.todo().getPriority().name(),
                        s.keywordScore(),
                        s.similarity().name()))
                .toList();
    }

    record SimilarTodoResponse(String id, String title, String priority, double score, String similarity) {}
}
