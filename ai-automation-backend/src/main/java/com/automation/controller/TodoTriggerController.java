package com.automation.controller;

import com.automation.dto.request.CreateTodoTriggerRequest;
import com.automation.dto.response.TodoTriggerResponse;
import com.automation.mapper.TodoTriggerMapper;
import com.automation.service.trigger.TodoTriggerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/todo-triggers")
@RequiredArgsConstructor
public class TodoTriggerController {

    private final TodoTriggerService service;
    private final TodoTriggerMapper mapper;

    @GetMapping
    public List<TodoTriggerResponse> listAll() {
        return mapper.toResponseList(service.findAll());
    }

    @GetMapping("/{id}")
    public TodoTriggerResponse getById(@PathVariable String id) {
        return mapper.toResponse(service.findById(id));
    }

    @GetMapping("/workflow/{workflowId}")
    public List<TodoTriggerResponse> getByTargetWorkflow(@PathVariable String workflowId) {
        return mapper.toResponseList(service.findByTargetWorkflowId(workflowId));
    }

    @PostMapping
    public ResponseEntity<TodoTriggerResponse> create(
            @Valid @RequestBody CreateTodoTriggerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toResponse(service.create(request)));
    }

    @PostMapping("/{id}/enable")
    public TodoTriggerResponse enable(@PathVariable String id) {
        return mapper.toResponse(service.enable(id));
    }

    @PostMapping("/{id}/disable")
    public TodoTriggerResponse disable(@PathVariable String id) {
        return mapper.toResponse(service.disable(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
